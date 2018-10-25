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

import scala.annotation.StaticAnnotation
import scala.collection.mutable.ListBuffer
import scala.language.experimental.macros
import scala.reflect.macros.blackbox

private[mxnet] class AddSymbolFunctions(isContrib: Boolean) extends StaticAnnotation {
  private[mxnet] def macroTransform(annottees: Any*) = macro SymbolImplMacros.addDefs
}
private[mxnet] class AddSymbolAPIs(isContrib: Boolean) extends StaticAnnotation {
  private[mxnet] def macroTransform(annottees: Any*) = macro SymbolImplMacros.typeSafeAPIDefs
}
private[mxnet] class AddSymbolRandomAPIs(isContrib: Boolean) extends StaticAnnotation {
  private[mxnet] def macroTransform(annottees: Any*) = macro SymbolImplMacros.typedRandomAPIDefs
}

private[mxnet] object SymbolImplMacros extends GeneratorBase {
  type SymbolArg = Arg
  type SymbolFunction = Func
  
  // scalastyle:off havetype
  def addDefs(c: blackbox.Context)(annottees: c.Expr[Any]*) = {
    impl(c)(annottees: _*)
  }
  def typeSafeAPIDefs(c: blackbox.Context)(annottees: c.Expr[Any]*) = {
    typedAPIImpl(c)(annottees: _*)
  }
  def typedRandomAPIDefs(c: blackbox.Context)(annottees: c.Expr[Any]*) = {
    typedRandomAPIImpl(c)(annottees: _*)
  }
  // scalastyle:on havetype

  private val symbolFunctions: List[SymbolFunction] = buildFunctionList(true)

  /**
    * Implementation for fixed input API structure
    */
  private def impl(c: blackbox.Context)(annottees: c.Expr[Any]*): c.Expr[Any] = {
    import c.universe._

    val isContrib: Boolean = c.prefix.tree match {
      case q"new AddSymbolFunctions($b)" => c.eval[Boolean](c.Expr(b))
    }

    val newSymbolFunctions = {
      if (isContrib) symbolFunctions.filter(
        func => func.name.startsWith("_contrib_") || !func.name.startsWith("_"))
      else symbolFunctions.filterNot(_.name.startsWith("_"))
    }

    val functionDefs = newSymbolFunctions map { symbolfunction =>
      val funcName = symbolfunction.name
      val tName = TermName(funcName)
      q"""
            def $tName(name : String = null, attr : Map[String, String] = null)
            (args : org.apache.mxnet.Symbol*)(kwargs : Map[String, Any] = null)
             : org.apache.mxnet.Symbol = {
              createSymbolGeneral($funcName,name,attr,args,kwargs)
              }
         """.asInstanceOf[DefDef]
    }

    structGeneration(c)(functionDefs, annottees: _*)
  }

  /**
    * Implementation for Dynamic typed API Symbol.api.<functioname>
    */
  private def typedAPIImpl(c: blackbox.Context)(annottees: c.Expr[Any]*): c.Expr[Any] = {
    import c.universe._

    val isContrib: Boolean = c.prefix.tree match {
      case q"new AddSymbolAPIs($b)" => c.eval[Boolean](c.Expr(b))
    }

    // Defines Operators that should not generated
    val notGenerated = Set("Custom")

    // TODO: Put Symbol.api.foo --> Stable APIs
    // Symbol.contrib.bar--> Contrib APIs
    val newSymbolFunctions = {
      if (isContrib) symbolFunctions.filter(
        func => func.name.startsWith("_contrib_") || !func.name.startsWith("_"))
      else symbolFunctions.filterNot(_.name.startsWith("_"))
    }.filterNot(ele => notGenerated.contains(ele.name))

    val functionDefs = newSymbolFunctions.map(f => buildTypedFunction(c)(f))
    structGeneration(c)(functionDefs, annottees: _*)
  }

  private def typedRandomAPIImpl(c: blackbox.Context)(annottees: c.Expr[Any]*): c.Expr[Any] = {
    val rndFunctions = symbolFunctions
      .filter(f => f.name.startsWith("_sample_") || f.name.startsWith("_random_"))
      .map(f => f.copy(name = f.name.stripPrefix("_")))

    val functionDefs = rndFunctions.map(f => buildTypedFunction(c)(f))
    structGeneration(c)(functionDefs, annottees: _*)
  }

  private def buildTypedFunction(c: blackbox.Context)
                                (symbolfunction: SymbolFunction): c.universe.DefDef = {
    import c.universe._

    val returnType = "org.apache.mxnet.Symbol"

    // Construct argument field
    val argDef = ListBuffer[String]()

    argDef ++= buildArgDefs(symbolfunction)
    argDef += "name : String = null"
    argDef += "attr : Map[String, String] = null"

    // Construct Implementation field
    var impl = ListBuffer[String]()
    impl += "val map = scala.collection.mutable.Map[String, Any]()"
    impl += "var args = Seq[org.apache.mxnet.Symbol]()"

    symbolfunction.listOfArgs.foreach({ symbolarg =>
      // Symbol arg implementation
      val symbolType = "org.apache.mxnet.Symbol"

      val base =
        if (symbolarg.argType.equals(s"Array[$symbolType]")) {
          if (symbolarg.isOptional)
            s"if (!${symbolarg.safeArgName}.isEmpty) args = ${symbolarg.safeArgName}.get.toSeq"
          else
            s"args = ${symbolarg.safeArgName}.toSeq"
        } else {
          if (symbolarg.isOptional) {
            // scalastyle:off
            s"if (!${symbolarg.safeArgName}.isEmpty) map(" + "\"" + symbolarg.argName + "\"" + s") = ${symbolarg.safeArgName}.get"
            // scalastyle:on
          }
          else "map(\"" + symbolarg.argName + "\"" + s") = ${symbolarg.safeArgName}"
        }

      impl += base
    })

    // scalastyle:off
    impl += "org.apache.mxnet.Symbol.createSymbolGeneral(\"" + symbolfunction.name + "\", name, attr, args, map.toMap)"
    // scalastyle:on

    // Combine and build the function string
    var finalStr = s"def ${symbolfunction.name}"
    finalStr += s" (${argDef.mkString(",")}) : $returnType"
    finalStr += s" = {${impl.mkString("\n")}}"
    c.parse(finalStr).asInstanceOf[DefDef]
  }

}
