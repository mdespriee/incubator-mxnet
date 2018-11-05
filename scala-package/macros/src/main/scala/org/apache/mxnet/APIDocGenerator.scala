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

package org.apache.mxnet

import java.io._
import java.security.MessageDigest

import scala.collection.mutable.ListBuffer

/**
  * This object will generate the Scala documentation of the new Scala API
  * Two file namely: SymbolAPIBase.scala and NDArrayAPIBase.scala
  * The code will be executed during Macros stage and file live in Core stage
  */
private[mxnet] object APIDocGenerator extends GeneratorBase with RandomHelpers {

  def main(args: Array[String]): Unit = {
    val FILE_PATH = args(0)
    val hashCollector = ListBuffer[String]()
    hashCollector += typeSafeClassGen(FILE_PATH, true)
    hashCollector += typeSafeClassGen(FILE_PATH, false)
    hashCollector += typeSafeRandomClassGen(FILE_PATH, true)
    hashCollector += typeSafeRandomClassGen(FILE_PATH, false)
    hashCollector += nonTypeSafeClassGen(FILE_PATH, true)
    hashCollector += nonTypeSafeClassGen(FILE_PATH, false)
    val finalHash = hashCollector.mkString("\n")
  }

  def MD5Generator(input: String): String = {
    val md = MessageDigest.getInstance("MD5")
    md.update(input.getBytes("UTF-8"))
    val digest = md.digest()
    org.apache.commons.codec.binary.Base64.encodeBase64URLSafeString(digest)
  }

  def typeSafeClassGen(FILE_PATH: String, isSymbol: Boolean): String = {
    val generated = typeSafeFunctionsToGenerate(isSymbol, isContrib = false)
      .map { func =>
        val scalaDoc = generateAPIDocFromBackend(func)
        val decl = generateAPISignature(func, isSymbol)
        s"$scalaDoc\n$decl"
      }

    writeFile(
      FILE_PATH,
      "package org.apache.mxnet",
      if (isSymbol) "SymbolAPIBase" else "NDArrayAPIBase",
      "import org.apache.mxnet.annotation.Experimental",
      generated)
  }

  def typeSafeRandomClassGen(FILE_PATH: String, isSymbol: Boolean): String = {
    val generated = typeSafeRandomFunctionsToGenerate(isSymbol)
      .map { func =>
        val scalaDoc = generateAPIDocFromBackend(func)
        val typeParameter = randomGenericTypeSpec(isSymbol, false)
        val decl = generateAPISignature(func, isSymbol, typeParameter)
        s"$scalaDoc\n$decl"
      }

    writeFile(
      FILE_PATH,
      "package org.apache.mxnet",
      if (isSymbol) "SymbolRandomAPIBase" else "NDArrayRandomAPIBase",
      """import org.apache.mxnet.annotation.Experimental
        |import scala.reflect.runtime.universe.TypeTag""".stripMargin,
      generated)
  }

  def nonTypeSafeClassGen(FILE_PATH: String, isSymbol: Boolean): String = {
    val absFuncs = functionsToGenerate(isSymbol, isContrib = false)
      .map { func =>
        val scalaDoc = generateAPIDocFromBackend(func, false)
        if (isSymbol) {
          s"""$scalaDoc
             |def ${func.name}(name : String = null, attr : Map[String, String] = null)
             |    (args : org.apache.mxnet.Symbol*)(kwargs : Map[String, Any] = null):
             |    org.apache.mxnet.Symbol
           """.stripMargin
        } else {
          s"""$scalaDoc
             |def ${func.name}(kwargs: Map[String, Any] = null)
             |    (args: Any*): org.apache.mxnet.NDArrayFuncReturn
             |
             |$scalaDoc
             |def ${func.name}(args: Any*): org.apache.mxnet.NDArrayFuncReturn
           """.stripMargin
        }
      }

    writeFile(
      FILE_PATH,
      "package org.apache.mxnet",
      if (isSymbol) "SymbolBase" else "NDArrayBase",
      "import org.apache.mxnet.annotation.Experimental",
      absFuncs)
  }

  def generateAPIDocFromBackend(func: Func, withParam: Boolean = true): String = {
    val desc = func.desc.split("\n")
      .mkString("  * <pre>", "\n  * ", "\n  * </pre>")

    val params = func.listOfArgs.map { absClassArg =>
      s"  * @param ${absClassArg.safeArgName}\t\t${absClassArg.argDesc}"
    }

    val returnType = s"  * @return ${func.returnType}"

    if (withParam) {
      s"""  /**
         |$desc
         |${params.mkString("\n")}
         |$returnType
         |  */""".stripMargin
    } else {
      s"""  /**
         |$desc
         |$returnType
         |  */""".stripMargin
    }
  }

  def generateAPISignature(func: Func, isSymbol: Boolean, typeParameter: String = ""): String = {
    val argDef = ListBuffer[String]()

    argDef ++= typedFunctionCommonArgDef(func)

    if (isSymbol) {
      argDef += "name : String = null"
      argDef += "attr : Map[String, String] = null"
    } else {
      argDef += "out : Option[NDArray] = None"
    }

    val returnType = func.returnType

    s"""@Experimental
       |def ${func.name}$typeParameter (${argDef.mkString(", ")}): $returnType""".stripMargin
  }

  def writeFile(FILE_PATH: String, packageDef: String, className: String,
                imports: String, absFuncs: Seq[String]): String = {

    val finalStr =
      s"""/*
         |* Licensed to the Apache Software Foundation (ASF) under one or more
         |* contributor license agreements.  See the NOTICE file distributed with
         |* this work for additional information regarding copyright ownership.
         |* The ASF licenses this file to You under the Apache License, Version 2.0
         |* (the "License"); you may not use this file except in compliance with
         |* the License.  You may obtain a copy of the License at
         |*
         |*    http://www.apache.org/licenses/LICENSE-2.0
         |*
         |* Unless required by applicable law or agreed to in writing, software
         |* distributed under the License is distributed on an "AS IS" BASIS,
         |* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
         |* See the License for the specific language governing permissions and
         |* limitations under the License.
         |*/
         |
         |$packageDef
         |
         |$imports
         |
         |// scalastyle:off
         |abstract class $className {
         |${absFuncs.mkString("\n")}
         |}""".stripMargin

    val pw = new PrintWriter(new File(FILE_PATH + s"$className.scala"))
    pw.write(finalStr)
    pw.close()
    MD5Generator(finalStr)
  }

}
