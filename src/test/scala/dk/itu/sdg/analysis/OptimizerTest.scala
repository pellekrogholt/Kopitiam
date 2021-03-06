package dk.itu.sdg.analysis

import scala.collection.immutable.{ HashSet, HashMap }
import org.scalatest.{ FlatSpec }
import org.scalatest.matchers.{ ShouldMatchers }
import org.scalatest.Ignore
import dk.itu.sdg.javaparser._
import dk.itu.sdg.analysis.Optimizer._

class ReadWriteVariablesOfStatement extends FlatSpec with ShouldMatchers {

    "SJVariableAccess" should "add variable to read set" in {
      rwOfStatement(SJVariableAccess("tmp_1")) should equal (Some(ReadsAndWrites(HashSet("tmp_1"),HashSet())))
    }

    "SJCall" should "add variables to both the read and write sets" in {
      val readAndWrite = rwOfStatement(SJCall(Some(SJVariableAccess("tmp_1")), SJVariableAccess("this"), "fac",List(SJBinaryExpression("-", SJVariableAccess("n"), SJLiteral("1")))))
      readAndWrite should equal (Some(ReadsAndWrites(HashSet("this", "n"), HashSet("tmp_1"))))
    }

    "SJAssignment" should "add variables to possible both read and write sets" in {
      val readAndWrite = rwOfStatement(SJAssignment(SJVariableAccess("x"),
        SJBinaryExpression("*", SJVariableAccess("n"), SJVariableAccess("tmp_1"))))
      readAndWrite should equal (Some(ReadsAndWrites(HashSet("tmp_1", "n"),HashSet("x"))))
    }
}

class RemoveTemporaryVariables extends FlatSpec with ShouldMatchers with ASTSpec {

  "Removing dead variables" should "not remove a dead variable in a while loop if it's read in the condition" in {
    val before = SJMethodDefinition(Set(Static()), "sum", "int", Nil, List(
      SJAssignment(SJVariableAccess("keepGoing"),SJLiteral("true")),
      SJAssignment(SJVariableAccess("sum"),SJLiteral("0")),
      SJWhile(SJBinaryExpression("==",SJVariableAccess("keepGoing"),SJLiteral("true")), List(
        SJAssignment(SJVariableAccess("sum"),SJBinaryExpression("+",SJVariableAccess("sum"),SJLiteral("1"))),
        SJConditional(SJBinaryExpression(">",SJVariableAccess("sum"),SJLiteral("42")),List(
          SJAssignment(SJVariableAccess("keepGoing"),SJLiteral("false"))
        ), Nil)
      )),
      SJReturn(SJVariableAccess("sum"))
    ), HashMap("keepGoing" -> "boolean", "sum" -> "int"))

    val after = before

    liveVariableRewrite(before) should equal (after)
  }

  it should ("not remove a dead variable if it's using SJNewExpression" +
             "in the assignment of the variable as it might have side-effects") in {

    val before = SJMethodDefinition(Set(Static()), "m","int",Nil,List(
                   SJNewExpression(SJVariableAccess("obviouslyDead"),"Number",List(SJLiteral("42"))),
                   SJReturn(SJLiteral("42"))
                 ),HashMap("obviouslyDead" -> "int"))

    val after = before

    liveVariableRewrite(before) should equal (after)

  }

  it should ("not remove a dead variable if it's using SJCall in the" +
             "assignment of the variable as it might have side-effects") in {

    val before = SJMethodDefinition(Set(Static()), "m","int",Nil,List(
                   SJCall(Some(SJVariableAccess("obviouslyDead")),SJVariableAccess("obj"),"randomInt",Nil),
                   SJReturn(SJLiteral("42"))
                 ),HashMap("obviouslyDead" -> "int"))

    val after = before

    liveVariableRewrite(before) should equal (after)

  }

  "Remove Superfluous Temporary Variables" should "replace tmp_1 with x in fac" in {

    val ast = getASTbyParsingFileNamed("Fac2.txt", List("src", "test", "resources", "javaparser", "source"))

    val after = List(SJClassDefinition(Set(),"Fac","",List(),List(
      SJMethodDefinition(Set(Static()), "fac", "int",List(SJArgument("n", "int")), List(
        SJConditional(SJBinaryExpression(">=", SJVariableAccess("n"), SJLiteral("0")),
          List(
            SJCall(Some(SJVariableAccess("x")),SJVariableAccess("this"),"fac",List(SJBinaryExpression("-", SJVariableAccess("n"), SJLiteral("1")))),
            SJAssignment(SJVariableAccess("x"), SJBinaryExpression("*", SJVariableAccess("n"), SJVariableAccess("x")))
          ),
          List(
           SJAssignment(SJVariableAccess("x"), SJLiteral("1"))
          )),
        SJReturn(SJVariableAccess("x"))),
        HashMap("x" -> "int", "n" -> "int", "this" -> "Fac")),
      SJConstructorDefinition(Set(Public()),"Fac",List(),List(),HashMap("this" -> "Fac"))),None, HashMap()))


    removeDeadVariables(ast) should equal (after)
  }

  it should "should not replace dead variable if it's used in more than one assignment inside the block" in {
    val before = SJMethodDefinition(Set(Static()), "fac", "int",
      List(SJArgument("n", "int")), List(
        SJAssignment(SJVariableAccess("tmp_1"), SJLiteral("42")),
        SJConditional(SJBinaryExpression(">=", SJVariableAccess("n"), SJLiteral("0")),
          List(
            SJAssignment(SJVariableAccess("tmp_1"),SJBinaryExpression("-",SJVariableAccess("tmp_1"),SJLiteral("1"))),
            SJCall(
              Some(SJVariableAccess("tmp_1")),
              SJVariableAccess("this"),
              "fac",
              List(SJBinaryExpression("-", SJVariableAccess("n"), SJLiteral("1")))
            ),
            SJAssignment(SJVariableAccess("x"), SJBinaryExpression("*", SJVariableAccess("n"), SJVariableAccess("tmp_1")))
          ),
          List(
            SJAssignment(SJVariableAccess("x"), SJLiteral("1"))
          )
        ),
        SJReturn(SJVariableAccess("x"))),
      HashMap("x" -> "int", "tmp_1" -> "int", "n" -> "int", "this" -> "Fac"))

    val after = before

    liveVariableRewrite(before) should equal (after)
  }

  it should "only replace a dead variable if it's of the same type." in {

    val before = SJMethodDefinition(Set(Static()), "test", "string", Nil,
      List(
        SJAssignment(SJVariableAccess("tmp_1"),SJLiteral("42")),
        SJAssignment(SJVariableAccess("x"),SJBinaryExpression("+", SJVariableAccess("test"),SJVariableAccess("tmp_1"))),
        SJReturn(SJVariableAccess("x"))
      ),
      HashMap("tmp_1" -> "int", "x" -> "string"))

    val after = before

    liveVariableRewrite(before) should equal (after)

  }

  /*
    This fails. It's not able to optimize the code yet.
  */
  "Parsing Conditional3.txt" should "produce the correct AST" in {

    val ast = getASTbyParsingFileNamed("Conditional3.txt", List("src", "test", "resources", "javaparser", "source"))

    val after = List(SJClassDefinition(Set(),"Foo","",List(),List(
      SJFieldDefinition(Set(),"c","int"),
      SJFieldDefinition(Set(),"b","int"),
      SJMethodDefinition(Set(),"bar","void",List(SJArgument("a","int")),
        List(
          SJFieldRead(SJVariableAccess("tmp_2"),SJVariableAccess("this"),"c"),
          SJConditional(SJBinaryExpression("==",SJVariableAccess("a"),SJVariableAccess("tmp_2")),
              List(SJFieldWrite(SJVariableAccess("this"),"b",SJLiteral("20"))),
              List(SJFieldWrite(SJVariableAccess("this"),"b",SJLiteral("30"))))),
        HashMap("this" -> "Foo", "a" -> "int", "tmp_2" -> "int")),
    SJConstructorDefinition(Set(Public()),"Foo",List(),List(),HashMap("this" -> "Foo"))),None,HashMap("b" -> "int", "c" -> "int")))

    removeDeadVariables(ast) should equal (after)
  }

  /*
    This fails. It shows that the dead variable rewrite currently isn't mature enough to deal with
    multiple occurences of a variable even though they could be optimized
  */
  "replacing multiple variables" should "replace a variable used at more places" in {

    val ast = getASTbyParsingFileNamed("MultipleOccurancesOfVariable.java", List("src", "test", "resources", "liveliness", "source"))

    val after = List(SJClassDefinition(Set(),"MultipleOccurancesOfVariable","",List(),
      List(
        SJFieldDefinition(Set(),"x","int"),
        SJMethodDefinition(Set(),"test","int",List(SJArgument("n","int")),List(
          SJFieldRead(SJVariableAccess("tmp_1"),SJVariableAccess("this"),"x"),
          SJConditional(SJBinaryExpression(">=",SJVariableAccess("n"),SJLiteral("0")),
            List(SJFieldWrite(SJVariableAccess("this"),"x",SJBinaryExpression("+",SJVariableAccess("tmp_1"),SJLiteral("1")))),
            List(SJFieldWrite(SJVariableAccess("this"),"x",SJBinaryExpression("-",SJVariableAccess("tmp_1"),SJLiteral("1"))))),
          SJFieldRead(SJVariableAccess("tmp_3"),SJVariableAccess("this"),"x"),
          SJReturn(SJVariableAccess("tmp_3"))),
      HashMap("this" -> "MultipleOccurancesOfVariable", "n" -> "int", "tmp_3" -> "int", "tmp_1" -> "int")),
      SJConstructorDefinition(Set(Public()),"MultipleOccurancesOfVariable",List(),List(),HashMap("this" -> "MultipleOccurancesOfVariable"))
    ),None,HashMap("x" -> "int")))

    removeDeadVariables(ast) should equal (after)
  }

}


