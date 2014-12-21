package com.stripe.brushfire.scalding

import com.stripe.brushfire._
import com.twitter.scalding._
import com.twitter.algebird._
import com.twitter.bijection._

import scala.util.Random

abstract class TrainerJob(args: Args) extends ExecutionJob[Unit](args) with Defaults {
  import TDsl._

  def execution = trainer.execution.unit
  def trainer: Trainer[_, _, _]
}

object TreeSource {
  def apply[K, V, T](path: String)(implicit inj: Injection[Tree[K, V, T], String]) = {
    implicit val bij = Injection.unsafeToBijection(inj).inverse
    typed.BijectedSourceSink[(Int, String), (Int, Tree[K, V, T])](TypedTsv(path))
  }
}

case class Trainer[K: Ordering, V, T: Monoid](
    trainingDataExecution: Execution[TypedPipe[Instance[K, V, T]]],
    samplerExecution: Execution[Sampler[K]],
    treeExecution: Execution[TypedPipe[(Int, Tree[K, V, T])]],
    unitExecution: Execution[Unit],
    reducers: Int) {

  private def stepPath(base: String, n: Int) = base + "/step_%02d".format(n)

  def execution = Execution.zip(treeExecution, unitExecution).unit

  def flatMapTrees(fn: ((TypedPipe[Instance[K, V, T]], Sampler[K], Iterable[(Int, Tree[K, V, T])])) => Execution[TypedPipe[(Int, Tree[K, V, T])]]) = {
    val newExecution = treeExecution
      .flatMap { trees =>
        Execution.zip(trainingDataExecution, samplerExecution, trees.toIterableExecution)
      }.flatMap(fn)
    copy(treeExecution = newExecution)
  }

  def flatMapSampler(fn: ((TypedPipe[Instance[K, V, T]], Sampler[K])) => Execution[Sampler[K]]) = {
    val newExecution = trainingDataExecution.zip(samplerExecution).flatMap(fn)
    copy(samplerExecution = newExecution)
  }

  def tee[A](fn: ((TypedPipe[Instance[K, V, T]], Sampler[K], Iterable[(Int, Tree[K, V, T])])) => Execution[A]): Trainer[K, V, T] = {
    val newExecution = treeExecution
      .flatMap { trees =>
        Execution.zip(trainingDataExecution, samplerExecution, trees.toIterableExecution)
      }.flatMap(fn)
    copy(unitExecution = unitExecution.zip(newExecution).unit)
  }

  def forceTrainingDataToDisk: Trainer[K, V, T] = {
    copy(trainingDataExecution = trainingDataExecution.flatMap { _.forceToDiskExecution })
  }

  def load(path: String)(implicit inj: Injection[Tree[K, V, T], String]): Trainer[K, V, T] = {
    copy(treeExecution = Execution.from(TypedPipe.from(TreeSource(path))))
  }

  /**
   * Update the leaves of the current trees from the training set.
   *
   * The leaves target distributions will be set to the summed distributions of the instances
   * in the training set that would get classified to them. Often used to initialize an empty tree.
   */
  def updateTargets(path: String)(implicit inj: Injection[Tree[K, V, T], String]): Trainer[K, V, T] = {
    flatMapTrees {
      case (trainingData, sampler, trees) =>
        lazy val treeMap = trees.toMap

        trainingData
          .flatMap { instance =>
            for (
              (treeIndex, tree) <- treeMap;
              i <- 1.to(sampler.timesInTrainingSet(instance.id, instance.timestamp, treeIndex)).toList;
              leafIndex <- tree.leafIndexFor(instance.features).toList
            ) yield (treeIndex, leafIndex) -> instance.target
          }
          .sumByKey
          .map { case ((treeIndex, leafIndex), target) => treeIndex -> Map(leafIndex -> target) }
          .group
          .withReducers(reducers)
          .sum
          .map {
            case (treeIndex, map) => {
              val newTree =
                treeMap(treeIndex)
                  .updateByLeafIndex { index => map.get(index).map { t => LeafNode(index, t) } }

              treeIndex -> newTree
            }
          }.writeThrough(TreeSource(path))
    }
  }

  /**
   * expand each tree by one level, by attempting to split every leaf.
   * @param path where to save the new tree
   * @param splitter the splitter to use to generate candidate splits for each leaf
   * @param error the error function to use to decide which split to use for each leaf
   */
  def expand[E](path: String)(implicit splitter: Splitter[V, T], error: Error[T, E], stopper: Stopper[T], inj: Injection[Tree[K, V, T], String]) = {
    flatMapTrees {
      case (trainingData, sampler, trees) =>
        implicit val splitSemigroup = new SplitSemigroup[K, V, T, E](error.ordering)
        implicit val jdSemigroup = splitter.semigroup
        lazy val treeMap = trees.toMap

        val stats =
          trainingData
            .flatMap { instance =>
              lazy val features = instance.features.mapValues { value => splitter.create(value, instance.target) }

              for (
                (treeIndex, tree) <- treeMap;
                i <- 1.to(sampler.timesInTrainingSet(instance.id, instance.timestamp, treeIndex)).toList;
                leaf <- tree.leafFor(instance.features).toList if stopper.shouldSplit(leaf.target) && stopper.shouldSplitDistributed(leaf.target);
                (feature, stats) <- features if (sampler.includeFeature(feature, treeIndex, leaf.index))
              ) yield (treeIndex, leaf.index, feature) -> stats
            }

        val splits =
          stats
            .group
            .sum
            .flatMap {
              case ((treeIndex, leafIndex, feature), target) =>
                treeMap(treeIndex).leafAt(leafIndex).toList.flatMap { leaf =>
                  splitter
                    .split(leaf.target, target)
                    .flatMap { split =>
                      split.trainingError(error).map { err =>
                        treeIndex -> Map(leafIndex -> (feature, split, err))
                      }
                    }
                }
            }

        val emptySplits = TypedPipe.from(0.until(sampler.numTrees))
          .map { i => i -> Map[Int, (K, Split[V, T], E)]() }

        (splits ++ emptySplits)
          .group
          .withReducers(reducers)
          .sum
          .map {
            case (treeIndex, map) =>
              val newTree =
                treeMap(treeIndex)
                  .growByLeafIndex { index =>
                    for (
                      (feature, split, _) <- map.get(index).toList;
                      (predicate, target) <- split.predicates
                    ) yield (feature, predicate, target)
                  }

              treeIndex -> newTree
          }.writeThrough(TreeSource(path))
    }
  }

  /** produce an error object from the current trees and the validation set */
  def validate[E](error: Error[T, E])(fn: ValuePipe[E] => Execution[_]) = {
    tee {
      case (trainingData, sampler, trees) =>
        lazy val treeMap = trees.toMap

        val err =
          trainingData
            .flatMap { instance =>
              val predictions =
                for (
                  (treeIndex, tree) <- treeMap if sampler.includeInValidationSet(instance.id, instance.timestamp, treeIndex);
                  target <- tree.targetFor(instance.features).toList
                ) yield target

              if (predictions.isEmpty)
                None
              else
                Some(error.create(instance.target, predictions))
            }
            .sum(error.semigroup)
        fn(err)
    }
  }

  /**
   *  featureImportance should: shuffle data randomly (group on something random then sort on something random?),
   * then stream through and have each instance pick one feature value at random to pass on to the following instance.
   * then group by permuted feature and compare error.
   * @param error
   * @tparam E
   * @return
   */
  def featureImportance[E](error: Error[T, E])(fn: TypedPipe[(K, E)] => Execution[_]) = {
    lazy val r = new Random(123)
    tee {
      case (trainingData, sampler, trees) =>
        lazy val treeMap = trees.toMap

        val permutedFeatsPipe = trainingData.groupRandomly(10).sortBy { _ => r.nextDouble() }.mapValueStream {
          instanceIterator =>
            instanceIterator.sliding(2)
              .flatMap {
                case List(prevInst, instance) =>
                  val treesForInstance = treeMap.filter {
                    case (treeIndex, tree) => sampler.includeInValidationSet(instance.id, instance.timestamp, treeIndex)
                  }.values

                  treesForInstance.map { tree =>
                    val featureToPermute = r.shuffle(prevInst.features).head
                    val permuted = instance.features + featureToPermute
                    val instanceErr = error.create(instance.target, tree.targetFor(permuted))
                    (featureToPermute._1, instanceErr)
                  }
                case _ =>
                  Nil
              }
        }.values

        val summed = permutedFeatsPipe.groupBy(_._1)
          .mapValues(_._2)
          .sum(error.semigroup)
        fn(summed.toTypedPipe)
    }
  }

  /** recursively expand multiple times, writing out the new tree at each step */
  def expandTimes[E](base: String, times: Int)(implicit splitter: Splitter[V, T], error: Error[T, E], stopper: Stopper[T], inj: Injection[Tree[K, V, T], String]) = {
    updateTargets(stepPath(base, 0))
      .expandFrom(base, 1, times)
  }

  def expandFrom[E](base: String, step: Int, to: Int)(implicit splitter: Splitter[V, T], error: Error[T, E], stopper: Stopper[T], inj: Injection[Tree[K, V, T], String]): Trainer[K, V, T] = {
    if (step > to)
      this
    else {
      expand(stepPath(base, step))(splitter, error, stopper, inj)
        .expandFrom(base, step + 1, to)
    }
  }

  def expandInMemory[E](path: String, times: Int)(implicit splitter: Splitter[V, T], error: Error[T, E], stopper: Stopper[T], inj: Injection[Tree[K, V, T], String]): Trainer[K, V, T] = {
    flatMapTrees {
      case (trainingData, sampler, trees) =>

        lazy val treeMap = trees.toMap
        lazy val r = new Random(123)

        val expansions =
          trainingData
            .flatMap { instance =>
              for (
                (treeIndex, tree) <- treeMap;
                i <- 1.to(sampler.timesInTrainingSet(instance.id, instance.timestamp, treeIndex)).toList;
                leaf <- tree.leafFor(instance.features).toList if stopper.shouldSplit(leaf.target) && (r.nextDouble < stopper.samplingRateToSplitLocally(leaf.target))
              ) yield (treeIndex, leaf.index) -> instance
            }
            .group
            .forceToReducers
            .toList
            .map {
              case ((treeIndex, leafIndex), instances) =>
                val target = Monoid.sum(instances.map { _.target })
                val leaf = LeafNode[K, V, T](0, target)
                val expanded = Tree.expand(times, leaf, splitter, error, stopper, instances)
                treeIndex -> List(leafIndex -> expanded)
            }

        val emptyExpansions = TypedPipe.from(0.until(sampler.numTrees))
          .map { i => i -> List[(Int, Node[K, V, T])]() }

        (expansions ++ emptyExpansions)
          .group
          .withReducers(reducers)
          .sum
          .map {
            case (treeIndex, list) =>

              val map = list.toMap

              val newTree =
                treeMap(treeIndex)
                  .updateByLeafIndex { index => map.get(index) }

              treeIndex -> newTree
          }.writeThrough(TreeSource(path))
    }
  }

  /** add out of time validation */
  def outOfTime(quantile: Double = 0.8) = {
    flatMapSampler {
      case (trainingData, sampler) =>

        implicit val qtree = new QTreeSemigroup[Long](6)

        trainingData
          .map { instance => QTree(instance.timestamp) }
          .sum
          .map { q => q.quantileBounds(quantile)._2.toLong }
          .toIterableExecution
          .map { thresholds => OutOfTimeSampler(sampler, thresholds.head) }
    }
  }
}

object Trainer {
  val MaxReducers = 20

  def apply[K: Ordering, V, T: Monoid](trainingData: TypedPipe[Instance[K, V, T]], sampler: Sampler[K]): Trainer[K, V, T] = {
    val empty = 0.until(sampler.numTrees).map { treeIndex => (treeIndex, Tree.empty[K, V, T](Monoid.zero)) }
    Trainer(
      Execution.from(trainingData),
      Execution.from(sampler),
      Execution.from(TypedPipe.from(empty)),
      Execution.from(()),
      sampler.numTrees.min(MaxReducers))
  }
}

class SplitSemigroup[K, V, T, E](ordering: Ordering[E]) extends Semigroup[(K, Split[V, T], E)] {
  def plus(a: (K, Split[V, T], E), b: (K, Split[V, T], E)) = {
    if (ordering.lt(a._3, b._3))
      a
    else
      b
  }
}
