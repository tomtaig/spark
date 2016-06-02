/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.spark.mllib.fpm

import scala.collection.JavaConverters._
import scala.reflect.ClassTag

import org.apache.spark.annotation.{Experimental, Since}
import org.apache.spark.api.java.JavaRDD
import org.apache.spark.api.java.JavaSparkContext.fakeClassTag
import org.apache.spark.internal.Logging
import org.apache.spark.mllib.fpm.AssociationRules.Rule
import org.apache.spark.mllib.fpm.FPGrowth.FreqItemset
import org.apache.spark.rdd.RDD

/**
 * :: Experimental ::
 *
 * Generates association rules from a [[RDD[FreqItemset[Item]]]. This method only generates
 * association rules which have a single item as the consequent.
 *
 */
@Since("1.5.0")
@Experimental
class AssociationRules private[fpm] (
    private var minConfidence: Double) extends Logging with Serializable {

  /**
   * Constructs a default instance with default parameters {minConfidence = 0.8}.
   */
  @Since("1.5.0")
  def this() = this(0.8)

  /**
   * Sets the minimal confidence (default: `0.8`).
   */
  @Since("1.5.0")
  def setMinConfidence(minConfidence: Double): this.type = {
    require(minConfidence >= 0.0 && minConfidence <= 1.0,
      s"Minimal confidence must be in range [0, 1] but got ${minConfidence}")
    this.minConfidence = minConfidence
    this
  }

  /**
   * Computes the association rules with confidence above [[minConfidence]].
   * @param freqItemsets frequent itemset model obtained from [[FPGrowth]]
   * @param frequencies item frequency counts for the entire item set
   * @param itemCount the count of all the itemsets
   * @return a [[Set[Rule[Item]]] containing the association rules.
   *
   */
  @Since("1.5.0")
  def run[Item: ClassTag](freqItemsets: RDD[FreqItemset[Item]], frequencies: RDD[(Item, Int)], itemCount: Long): RDD[Rule[Item]] = {

    // For candidate rule X => Y, generate (X, (Y, freq(X union Y)))
    val candidates = freqItemsets.flatMap { itemset =>
        val items = itemset.items
        items.flatMap { item =>
            items.partition(item2 => item2 == item) match {
              case (consequent, antecedent) if !antecedent.isEmpty => Some((antecedent.toSeq, (consequent.toSeq, itemset.freq)))
              case item2 => None
            }
        }
    }

    // generate (X, (((Y, freq(X union Y)), freq(X)), freq(Y')))
    candidates
        .join(freqItemsets.map(x => (x.items.toSeq, x.freq)))
        .map { case (antecedent, ((consequent, freqUnion), freqAntecedent)) =>
            (consequent, ((antecedent, freqUnion), freqAntecedent))
        }
        .join(frequencies.map(item => (Seq(item._1), item._2)))
        .map { case (consequent, (((antecedent, freqUnion), freqAntecedent), freqConsequent)) =>
            new Rule(antecedent.toArray, consequent.toArray, freqAntecedent.toInt, freqUnion.toInt, freqConsequent.toDouble, itemCount)
        }
        .filter(_.confidence >= minConfidence)
  }

  /** Java-friendly version of [[run]]. */
  @Since("1.5.0")
  def run[Item](freqItemsets: JavaRDD[FreqItemset[Item]], frequencies: JavaRDD[(Item, Int)], totalItemCount: Long): JavaRDD[Rule[Item]] = {
    val tag = fakeClassTag[Item]
    run(freqItemsets.rdd, frequencies.rdd, totalItemCount)(tag)
  }
}

@Since("1.5.0")
object AssociationRules {

  /**
   * :: Experimental ::
   *
   * An association rule between sets of items.
   * @param antecedent hypotheses of the rule. Java users should call [[Rule#javaAntecedent]]
   *                   instead.
   * @param consequent conclusion of the rule. Java users should call [[Rule#javaConsequent]]
   *                   instead.
   * @param totalItemCount the count of all the itemsets
   * @tparam Item item type
   *
   */
  @Since("1.5.0")
  @Experimental
  class Rule[Item] private[fpm] (
      @Since("1.5.0") val antecedent: Array[Item],
      @Since("1.5.0") val consequent: Array[Item],
      freqUnion: Double,
      freqAntecedent: Double,
      freqConsequent: Double,
      totalItemCount: Long) extends Serializable {

    /**
     * Returns the confidence of the rule.
     *
     */
    @Since("1.5.0")
    def confidence: Double = freqUnion.toDouble / freqAntecedent

    /**
     * Returns the lift of the rule.
     *
     */
    def lift: Double = confidence / (freqConsequent / totalItemCount.toDouble)
    
    require(antecedent.toSet.intersect(consequent.toSet).isEmpty, {
      val sharedItems = antecedent.toSet.intersect(consequent.toSet)
      s"A valid association rule must have disjoint antecedent and " +
        s"consequent but ${sharedItems} is present in both."
    })

    /**
     * Returns antecedent in a Java List.
     *
     */
    @Since("1.5.0")
    def javaAntecedent: java.util.List[Item] = {
      antecedent.toList.asJava
    }

    /**
     * Returns consequent in a Java List.
     *
     */
    @Since("1.5.0")
    def javaConsequent: java.util.List[Item] = {
      consequent.toList.asJava
    }

    override def toString: String = {
      s"${antecedent.mkString("{", ",", "}")} => " +
        s"${consequent.mkString("{", ",", "}")}: ${freqAntecedent} ${freqUnion} ${freqConsequent} ${confidence} ${lift}"
    }
  }
}
