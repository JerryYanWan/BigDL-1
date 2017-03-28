/*
 * Copyright 2016 The BigDL Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intel.analytics.bigdl.example.deepspeech2

import breeze.numerics.abs
import com.intel.analytics.bigdl._
import com.intel.analytics.bigdl.nn._
import com.intel.analytics.bigdl.optim._
import com.intel.analytics.bigdl.tensor.TensorNumericMath.TensorNumeric
import com.intel.analytics.bigdl.tensor.{Storage, Tensor}
import org.apache.log4j.{Level, Logger}
import org.apache.spark.sql.SparkSession

import scala.collection.mutable.ArrayBuffer
import scala.io.Source
import scala.language.existentials
import scala.reflect.ClassTag

class DeepSpeech2[T : ClassTag](depth: Int = 1)
                               (implicit ev: TensorNumeric[T]) {

  /**
   * The configuration of convolution for dp2.
   */
  val nInputPlane = 1
  val nOutputPlane = 1152
  val kW = 11
  val kH = 13
  val dW = 3
  val dH = 1
  val padW = 5
  val padH = 0
  val conv = SpatialConvolution(nInputPlane, nOutputPlane,
    kW, kH, dW, dH, padW, padH)

  val nOutputDim = 2
  val outputHDim = 3
  val outputWDim = 4
  val inputSize = nOutputPlane
  val hiddenSize = nOutputPlane
  val nChar = 29

  /**
   * append BiRNN layers to the deepspeech model.
   * @param inputSize
   * @param hiddenSize
   * @param isCloneInput
   * @param depth
   * @return
   */
  def addBRNN(inputSize: Int, hiddenSize: Int, isCloneInput: Boolean, depth: Int): Module[T] = {
    Sequential()
      .add(BiRecurrent[T](JoinTable[T](2, 2), isCloneInput = isCloneInput)
        .add(RnnCell[T](inputSize, hiddenSize, HardTanh[T](0, 20, true))).setName("birnn" + depth))
  }

  val brnn = Sequential()
  var i = 1
  while (i <= depth) {
    if (i == 1) {
      brnn.add(addBRNN(inputSize, hiddenSize, isCloneInput = true, i))
    } else {
      brnn.add(addBRNN(hiddenSize, hiddenSize, isCloneInput = false, i))
    }
    i += 1
  }

  val linear1 = TimeDistributed[T](Linear[T](hiddenSize, hiddenSize, withBias = false))
  val linear2 = TimeDistributed[T](Linear[T](hiddenSize, nChar, withBias = false))

  /**
   * The deep speech2 model.
   *****************************************************************************************
   *
   *   Convolution -> ReLU -> BiRNN (9 layers) -> Linear -> ReLUClip (HardTanh) -> Linear
   *
   *****************************************************************************************
   */
  val model = Sequential[T]()
    .add(conv)
    .add(ReLU[T]())
    .add(Transpose(Array((nOutputDim, outputWDim), (outputHDim, outputWDim))))
    .add(Squeeze(4))
    .add(brnn)
    .add(linear1)
    .add(HardTanh[T](0, 20, true))
    .add(linear2)

  def reset(): Unit = {
    conv.weight.fill(ev.fromType[Double](0.0))
    conv.bias.fill(ev.fromType[Double](0.0))
  }

  def evaluate(inputs: Array[Double], expectOutputs: Array[Double], logger: Logger): Unit = {

    /**
     ********************************************************
     *  For my small test, I cut sample size to 198,
     *  please modify it according to your input sample.
     ********************************************************
     */
    val input = Tensor[Double](Storage(inputs), 1, Array(1, 1, 13, 198))
    val output = model.forward(input).toTensor[Double]

    var idx = 0
    var accDiff = 0.0
    output.apply1(x => {
      if (x == 0) {
        require(math.abs(x - expectOutputs(idx)) < 1e-2,
          "output does not concord to each other " +
            s"x = ${x}, expectX = ${expectOutputs(idx)}, idx = ${idx}")
        accDiff += math.abs(x - expectOutputs(idx))
      } else {
        require(math.abs(x - expectOutputs(idx)) / x < 1e-1,
          "output does not concord to each other " +
            s"x = ${x}, expectX = ${expectOutputs(idx)}, idx = ${idx}")
        accDiff += math.abs(x - expectOutputs(idx))
      }
      idx += 1
      x
    })

    logger.info("model inference finish!")
    logger.info("total relative error is : " + accDiff)
  }


  def setConvWeight(weights: Array[T]): Unit = {
    val temp = Tensor[T](Storage(weights), 1, Array(1, 1152, 1, 13, 11))
    conv.weight.set(Storage[T](weights), 1, conv.weight.size())

  }

  /**
   * load in the nervana's dp2 BiRNN model parameters
   * @param weights
   */
  def setBiRNNWeight(weights: Array[Array[T]]): Unit = {
      val parameters = brnn.parameters()._1
      // six tensors per brnn layer
      val numOfParams = 6
      for (i <- 0 until depth) {
        var offset = 1
        for (j <- 0 until numOfParams) {
          val length = parameters(i * numOfParams + j).nElement()
          val size = parameters(i * numOfParams + j).size
          parameters(i * numOfParams + j).set(Storage[T](weights(i)), offset, size)
          offset += length
        }
      }
  }

  /**
   * load in the nervana's dp2 Affine model parameters
   * @param weights
   * @param num
   */
  def setLinear0Weight(weights: Array[T], num: Int): Unit = {
    if (num == 0) {
      linear1.parameters()._1(0)
        .set(Storage[T](weights), 1, Array(1152, 2304))
    } else {
      linear2.parameters()._1(0)
        .set(Storage[T](weights), 1, Array(29, 1152))
    }
  }
}

object DeepSpeech2 {

  def main(args: Array[String]): Unit = {
//    Logger.getLogger("org").setLevel(Level.WARN)
//    Logger.getLogger("com").setLevel(Level.WARN)
    Logger.getLogger("org").setLevel(Level.OFF)
    Logger.getLogger("akka").setLevel(Level.OFF)
    val logger = Logger.getLogger(getClass)

    val spark = SparkSession.builder().master("local").appName("test").getOrCreate()

    /**
     ***************************************************************************
     *   Please configure your file path here:
     *   There should be 9 txt files for birnn.
     *   e.g. "/home/ywan/Documents/data/deepspeech/layer1.txt"
     ***************************************************************************
     */
    val inputPath = "/home/ywan/Documents/data/deepspeech/inputdata.txt"
    val nervanaOutputPath = "/home/ywan/Documents/data/deepspeech/output.txt"
    val convPath = "/home/ywan/Documents/data/deepspeech/dp2conv.txt"
    val birnnPath = "/home/ywan/Documents/data/deepspeech/layer"
    val linear1Path = "/home/ywan/Documents/data/deepspeech/linear0.txt"
    val linear2Path = "/home/ywan/Documents/data/deepspeech/linear1.txt"

    /**
     *********************************************************
     *    set the depth to be 9
     *    timeSeqLen is the final output sequence length
     *    The original timeSeqLen = 1000
     *    for my small test, I set it to be 66.
     *********************************************************
     */
    val depth = 6
    val convFeatureSize = 1152
    val birnnFeatureSize = 1152
    val linear1FeatureSize = 2304
    val linear2FeatureSize = 1152
    val timeSeqLen = 66

    logger.info("load in inputs and expectOutputs ..")
    val inputs = spark.sparkContext.textFile(inputPath)
      .map(_.toDouble).collect()
    val expectOutputs =
      spark.sparkContext.textFile(nervanaOutputPath)
      .map(_.split(',').map(_.toDouble)).flatMap(t => t).collect()

    /**
     *************************************************************************
     *    Loading model weights
     *    1. conv
     *    2. birnn
     *    3. linear1
     *    4. linear2
     *************************************************************************
     */

    logger.info("load in conv weights ..")
    val convWeights =
      spark.sparkContext.textFile(convPath)
        .map(_.split(',').map(_.toDouble)).flatMap(t => t).collect()

    logger.info("load in birnn weights ..")
    val weightsBirnn = new Array[Array[Double]](depth)
    for (i <- 0 until depth) {
      val birnnOrigin =
        spark.sparkContext.textFile(birnnPath + i + ".txt")
          .map(_.split(",").map(_.toDouble)).flatMap(t => t).collect()
      weightsBirnn(i) = convertBiRNN(birnnOrigin, birnnFeatureSize)
    }

    logger.info("load in linear1 weights ..")
    val linearOrigin0 =
      spark.sparkContext.textFile(linear1Path)
        .map(_.split(",").map(_.toDouble)).flatMap(t => t).collect()
    val weightsLinear0 = convertLinear(linearOrigin0, linear1FeatureSize)

    logger.info("load in linear2 weights ..")
    val linearOrigin1 =
      spark.sparkContext.textFile(linear2Path)
        .map(_.split(",").map(_.toDouble)).flatMap(t => t).collect()
    val weightsLinear1 = convertLinear(linearOrigin1, linear2FeatureSize)

    /**
     **************************************************************************
     *  set all the weights to the model and run the model
     *  dp2.evaluate()
     **************************************************************************
     */
    val dp2 = new DeepSpeech2[Double](depth)
    dp2.reset()
    dp2.setConvWeight(convert(convWeights, convFeatureSize))
    dp2.setBiRNNWeight(weightsBirnn)
    dp2.setLinear0Weight(weightsLinear0, 0)
    dp2.setLinear0Weight(weightsLinear1, 1)

    logger.info("run the model ..")
    dp2.evaluate(inputs, convert(expectOutputs, timeSeqLen), logger)
  }

  def convert(origin: Array[Double], channelSize: Int): Array[Double] = {
    val channel = channelSize
    val buffer = new ArrayBuffer[Double]()
    val groups = origin.grouped(channelSize).toArray

    for(i <- 0 until channel)
      for (j <- 0 until groups.length)
        buffer += groups(j)(i)
    buffer.toArray
  }

  def convertLinear(origin: Array[Double], channelSize: Int): Array[Double] = {
    val channel = channelSize
    val buffer = new ArrayBuffer[Double]()
    val groups = origin.grouped(channelSize).toArray

    for (j <- 0 until groups.length)
      for(i <- 0 until channel)
        buffer += groups(j)(i)
    buffer.toArray
  }

  def convertBiRNN(origin: Array[Double], channelSize: Int): Array[Double] = {
    val nIn = channelSize
    val nOut = channelSize
    val heights = 2 * (nIn + nOut + 1)
    val widths = nOut

    val buffer = new ArrayBuffer[Double]()
    val groups = origin.grouped(nOut).toArray

    /**
     * left-to-right rnn U, W, and bias
     */

    for (i <- 0 until nIn) {
      for (j <- 0 until nOut) {
        buffer += groups(i)(j)
      }
    }
    for (i <- 2 * nIn until (2 * nIn + nOut)) {
      for (j <- 0 until nOut) {
        buffer += groups(i)(j)
      }
    }
    for (i <- 2 * (nIn + nOut + 1) - 2 until 2 * (nIn + nOut + 1) - 1) {
      for (j <- 0 until nOut) {
        buffer += groups(i)(j)
      }
    }

    /**
     * right-to-left rnn U, W, and bias
     */

    for (i <- nIn until 2 * nIn) {
      for (j <- 0 until nOut) {
        buffer += groups(i)(j)
      }
    }
    for (i <- (2 * nIn + nOut) until (2 * nIn + 2 * nOut)) {
      for (j <- 0 until nOut) {
        buffer += groups(i)(j)
      }
    }
    for (i <- 2 * (nIn + nOut + 1) - 1 until 2 * (nIn + nOut + 1)) {
      for (j <- 0 until nOut) {
        buffer += groups(i)(j)
      }
    }
    buffer.toArray
  }
}
