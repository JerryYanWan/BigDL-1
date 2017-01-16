/*
 * Licensed to Intel Corporation under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * Intel Corporation licenses this file to You under the Apache License, Version 2.0
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

package com.intel.analytics.bigdl.dataset

import com.intel.analytics.bigdl.tensor.Tensor
import com.intel.analytics.bigdl.utils.T
import org.scalatest.{FlatSpec, Matchers}

class MiniBatchSpec extends FlatSpec with Matchers {
  "MiniBatch" should "initialize well for Tensor input" in {
    val input = Tensor[Float](Array(4, 2, 3)).randn()
    val target = Tensor[Float](Array(4)).randn()
    val miniBatch = MiniBatch[Float](input, target)
    miniBatch.size should be (4)
    miniBatch.narrow(1, 2, 2) should be ((input.narrow(1, 2, 2),
      target.narrow(1, 2, 2)))
  }
  "MiniBatch" should "initialize well for Table input" in {
    val input = T(Tensor[Float](Array(4, 2)).randn(), Tensor[Float](Array(3, 2)).randn())
    val target = T(Tensor[Float](Array(4)).randn(), Tensor[Float](Array(3)).randn())
    val miniBatch = MiniBatch[Float](input, target)
    miniBatch.size should be (2)
    miniBatch.narrow(1, 1, 1) should be ((input(1),
      target(1)))
  }
}