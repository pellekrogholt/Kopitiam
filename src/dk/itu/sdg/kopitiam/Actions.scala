/* (c) 2010-2011 Hannes Mehnert */

package dk.itu.sdg.kopitiam

import org.eclipse.ui.IWorkbenchWindowActionDelegate
import org.eclipse.core.commands.IHandler

abstract class KAction extends IWorkbenchWindowActionDelegate with IHandler {
  import org.eclipse.ui.IWorkbenchWindow
  import org.eclipse.jface.action.IAction
  import org.eclipse.jface.viewers.ISelection
  import org.eclipse.core.commands.{IHandlerListener,ExecutionEvent}

  override def init (w : IWorkbenchWindow) : Unit = ()
  override def dispose () : Unit = ()

  override def selectionChanged (a : IAction, s : ISelection) : Unit = ()

  private var handlers : List[IHandlerListener] = List[IHandlerListener]()
  override def addHandlerListener (h : IHandlerListener) : Unit = { handlers ::= h }
  override def removeHandlerListener (h : IHandlerListener) : Unit =
    { handlers = handlers.filterNot(_ == h) }

  override def run (a : IAction) : Unit = { doit }
  override def execute (ev : ExecutionEvent) : Object = { doit; null }

  override def isEnabled () : Boolean = true
  override def isHandled () : Boolean = true
  def doit () : Unit
}

abstract class KCoqAction extends KAction {
  import org.eclipse.jface.action.IAction
  import org.eclipse.jface.viewers.ISelection
  import org.eclipse.core.commands.{IHandlerListener,ExecutionEvent}

  private var first : Boolean = true
  override def selectionChanged (a : IAction, s : ISelection) : Unit =
    { if (first) { first = false; ActionDisabler.registeraction(a, start(), end()) } }

  override def isEnabled () : Boolean = {
    if (DocumentState.position == 0 && start)
      false
    else if (DocumentState.position + 1 >= DocumentState.content.length && end)
      false
    else
      true
  }
  override def run (a : IAction) : Unit = { doitH }
  override def execute (ev : ExecutionEvent) : Object = { doitH; null }

  import org.eclipse.ui.{IFileEditorInput, PlatformUI}
  import org.eclipse.core.resources.{IResource, IMarker}
  def doitH () : Unit = {
    ActionDisabler.disableAll
    val coqstarted = CoqTop.isStarted
    val acted = PlatformUI.getWorkbench.getActiveWorkbenchWindow.getActivePage.getActiveEditor
    if (DocumentState.activeEditor != acted) {
      if (DocumentState.resource != null)
        EclipseBoilerPlate.unmark
      if (DocumentState.coqmarker != null)
        DocumentState.coqmarker.delete
      DocumentState.coqmarker = null
      DocumentState.undoAll
      DocumentState.activeEditor = acted.asInstanceOf[CoqEditor]
      DocumentState.tick

      if (! coqstarted)
        CoqStartUp.start

      val initial =
        if (DocumentState.positionToShell.contains(0))
          DocumentState.positionToShell(0).globalStep
        else {
          Console.println("doitH: using 2 instead of registered position, since there is none")
          2
        }
      DocumentState.positionToShell.empty
      DocumentState.position = 0
      DocumentState.sendlen = 0
      CoqOutputDispatcher.goalviewer.clear

      if (coqstarted) {
        CoqStartUp.fini = false
        PrintActor.deregister(CoqOutputDispatcher)
        val shell = CoqState.getShell
        PrintActor.register(CoqStartUp)
        CoqTop.writeToCoq("Backtrack " + initial + " 0 " + shell.context.length + ".")
        while (! CoqStartUp.fini) { }
        CoqStartUp.fini = false
      }
    }
    doit
  }

  def start () : Boolean
  def end () : Boolean
}

object ActionDisabler {
  import org.eclipse.jface.action.IAction
  var actions : List[IAction] = List()
  var starts : List[Boolean] = List()
  var ends : List[Boolean] = List()
  def registeraction (act : IAction, start : Boolean, end : Boolean) : Unit = {
    actions ::= act
    starts ::= start
    ends ::= end
  }

  def disableAll () = {
    actions.foreach(_.setEnabled(false))
  }

  def enableMaybe () = {
    Console.println("maybe enable " + DocumentState.position + " len " + DocumentState.content.length)
    if (DocumentState.position == 0)
      enableStart
    else if (DocumentState.position + 1 >= DocumentState.content.length)
      actions.zip(ends).filterNot(_._2).map(_._1).foreach(_.setEnabled(true))
    else
      actions.foreach(_.setEnabled(true))
  }

  def enableStart () = {
    actions.zip(starts).filterNot(_._2).map(_._1).foreach(_.setEnabled(true))
  }
}

import dk.itu.sdg.coqparser.VernacularReserved
class CoqUndoAction extends KCoqAction with VernacularReserved {
  def lastqed (content : String, off : Int) : Int = {
    val lks = proofEnders.map(content.indexOf(_, off)).filterNot(_ == -1)
    if (lks.length == 0)
      -1
    else
      lks.reduceLeft(scala.math.min(_, _))
  }

  def eqmodws (content : String, off1 : Int, off2 : Int) : Boolean = {
    Console.println("eqmodws, inputs: off1: " + off1 + " off2: " + off2 + " content.length: " + content.length)
    if (off1 == 0)
      true
    else if (off2 == -1)
      content.drop(off1).trim.size == 0
    else
      content.take(off2).drop(off1).trim.size == 0
  }

  override def doit () : Unit = {
    val content = DocumentState.content
    val l = CoqTop.findPreviousCommand(content, DocumentState.position)
    Console.println("prev pos of " + DocumentState.position + " is " + l)
    if (l > -1) {
      DocumentState.realundo = true
      EclipseBoilerPlate.unmark
      val sh = CoqState.getShell
      val mn = lastqed(content, l)
      var off : Int = l
      Console.println("qed distance is " + (mn - l))
      if (mn > 0 && l > 0 && eqmodws(content, l, mn)) {
        Console.println("found qed-word nearby, better loop before last proof.")
        //Console.println("before loop " + content.take(content.indexOf("Proof.", off)).drop(off).trim.size)
        var deep : Int = 0
        while ((!eqmodws(content, off, content.indexOf("Proof.", off)) || deep != 0) && off > 0) {
          Console.println("in loop: " + deep + " current off is " + off)
          if (eqmodws(content, off, content.indexOf("Proof.", off)))
            deep -= 1
          off = CoqTop.findPreviousCommand(content, off)
          if (eqmodws(content, off, lastqed(content, off)))
            deep += 1
        }
        off = scala.math.max(0, CoqTop.findPreviousCommand(content, off))
        Console.println("found proof before  @" + off + ": " + content.drop(off).take(20))
      } //care about End Foo.: find Begin Foo., with Begin being Section or Module
      DocumentState.sendlen = DocumentState.position - off
      val oldsh =
        if (DocumentState.positionToShell.contains(off))
          DocumentState.positionToShell(off)
        else {
          Console.println("couldn't find shell for offset " + off + " in table with keys " + DocumentState.positionToShell.keys.toList.sort((x, y) => x < y))
          CoqShellTokens("Coq", 0, List(), 0)
        }
      val dropped = sh.context.length - oldsh.context.length
      CoqTop.writeToCoq("Backtrack " + oldsh.globalStep + " " + oldsh.localStep + " " + dropped + ".")
    } else
      ActionDisabler.enableMaybe
  }
  override def start () : Boolean = true
  override def end () : Boolean = false
}
object CoqUndoAction extends CoqUndoAction { }

class CoqRetractAction extends KCoqAction {
  override def doit () : Unit = {
    PrintActor.deregister(CoqOutputDispatcher)
    DocumentState.position = 0
    DocumentState.sendlen = 0
    PrintActor.register(CoqStartUp)
    val shell = CoqState.getShell
    DocumentState.undoAll
    EclipseBoilerPlate.unmark
    val initial =
      if (DocumentState.positionToShell.contains(0))
        DocumentState.positionToShell(0).globalStep
      else {
        Console.println("CoqRetractAction: retracting without position information, using 2")
        2
      }
    DocumentState.positionToShell.empty
    CoqTop.writeToCoq("Backtrack " + initial + " 0 " + shell.context.length + ".")
  }
  override def start () : Boolean = true
  override def end () : Boolean = false
}
object CoqRetractAction extends CoqRetractAction { }

class CoqStepAction extends KCoqAction {
  override def doit () : Unit = {
    //Console.println("run called, sending a command")
    val con = DocumentState.content
    val content = con.drop(DocumentState.position)
    if (content.length > 0) {
      val eoc = CoqTop.findNextCommand(content)
      //Console.println("eoc is " + eoc)
      if (eoc > 0) {
        DocumentState.sendlen = eoc
        val cmd = content.take(eoc).trim
        Console.println("command is (" + eoc + "): " + cmd)
        EclipseBoilerPlate.startProgress
        EclipseBoilerPlate.nameProgress(cmd)
        CoqTop.writeToCoq(cmd) //sends comments over the line
      }
    }
  }
  override def start () : Boolean = false
  override def end () : Boolean = true
}
object CoqStepAction extends CoqStepAction { }

class CoqStepAllAction extends KCoqAction {
  override def doit () : Unit = {
    //Console.println("registering CoqStepNotifier to PrintActor, now stepping")
    EclipseBoilerPlate.multistep = true
    PrintActor.register(new CoqStepNotifier())
    //we need to provoke first message to start callback loops
    CoqStepAction.doit()
  }
  override def start () : Boolean = false
  override def end () : Boolean = true
}
object CoqStepAllAction extends CoqStepAllAction { }

class CoqStepUntilAction extends KCoqAction {
  override def doit () : Unit = {
    doitReally(CoqTop.findPreviousCommand(DocumentState.content, EclipseBoilerPlate.getCaretPosition + 2), None)
  }

  def doitReally (togo : Int, dolater : Option[() => Unit]) = {
    //need to go back one more step
    //doesn't work reliable when inside a comment
    Console.println("togo is " + togo + ", curpos is " + EclipseBoilerPlate.getCaretPosition + ", docpos is " + DocumentState.position)
    if (DocumentState.position == togo) { } else
    if (DocumentState.position < togo) {
      EclipseBoilerPlate.multistep = true
      val coqs = new CoqStepNotifier()
      coqs.test = Some((x : Int) => x >= togo)
      coqs.later = dolater
      PrintActor.register(coqs)
      CoqStepAction.doit()
    } else { //Backtrack
      EclipseBoilerPlate.multistep = true
      val coqs = new CoqStepNotifier()
      coqs.test = Some((x : Int) => x <= togo)
      coqs.walker = CoqUndoAction.doit
      coqs.later = dolater
      coqs.undo = true
      PrintActor.register(coqs)
      CoqUndoAction.doit()
    }
  }
  override def start () : Boolean = false
  override def end () : Boolean = false
}
object CoqStepUntilAction extends CoqStepUntilAction { }

class RestartCoqAction extends KAction {
  override def doit () : Unit = {
    CoqTop.killCoq
    DocumentState.position = 0
    DocumentState.sendlen = 0
    DocumentState.undoAll
    EclipseBoilerPlate.unmark
    PrintActor.deregister(CoqOutputDispatcher)
    CoqStartUp.start
  }
}
object RestartCoqAction extends RestartCoqAction { }

class TranslateAction extends KAction {
  import org.eclipse.ui.handlers.HandlerUtil
  import org.eclipse.jface.viewers.IStructuredSelection
  import org.eclipse.jdt.core.ICompilationUnit
  import org.eclipse.core.resources.{IResource,IFile}
  import org.eclipse.core.commands.ExecutionEvent
  import java.io.{InputStreamReader,ByteArrayInputStream}
  import scala.util.parsing.input.StreamReader

  import dk.itu.sdg.javaparser.JavaAST
  object JavaTC extends JavaAST { }

  override def execute (ev : ExecutionEvent) : Object = {
    Console.println("execute translation!")
    val sel = HandlerUtil.getActiveMenuSelection(ev).asInstanceOf[IStructuredSelection]
    val fe = sel.getFirstElement
    Console.println("fe is " + fe + " type " + fe.asInstanceOf[AnyRef].getClass)
    if (fe.isInstanceOf[IFile])
      translate(fe.asInstanceOf[IFile])
    null
  }

  def translate (file : IFile) : Unit = {
    val nam = file.getName
    if (nam.endsWith(".java")) {
      val trfi = file.getProject.getFile(nam + ".v") //TODO: find a suitable location!
      val is = StreamReader(new InputStreamReader(file.getContents))
      if (trfi.exists)
        trfi.delete(true, false, null)
      trfi.create(new ByteArrayInputStream(JavaTC.parse(is, nam.substring(0, nam.indexOf(".java"))).getBytes), IResource.NONE, null)
      Console.println("translated file " + nam)
    } else
      Console.println("wasn't a java file")
  }

  override def doit () : Unit = ()
}
object TranslateAction extends TranslateAction { }

object CoqStartUp extends CoqCallback {
  private var first : Boolean = true
  var fini : Boolean = false

  def start () : Unit = {
    if (! CoqTop.isStarted) {
      PrintActor.register(CoqStartUp)
      if (EclipseConsole.out == null)
        EclipseConsole.initConsole
      PrintActor.stream = EclipseConsole.out
      if (! CoqTop.startCoq)
        EclipseBoilerPlate.warnUser("No Coq", "No Coq binary found, please specify one in the Kopitiam preferences page")
      else {
        while (!fini) { }
        fini = false
      }
      PrintActor.deregister(CoqStartUp)
    }
  }

  override def dispatch (x : CoqResponse) : Unit = {
    x match {
      case CoqShellReady(m, t) =>
        if (first) {
          CoqTop.writeToCoq("Add LoadPath \"" + EclipseBoilerPlate.getProjectDir + "\".")
          first = false
        } else {
          PrintActor.deregister(CoqStartUp)
          PrintActor.register(CoqOutputDispatcher)
          first = true
          fini = true
          ActionDisabler.enableMaybe
        }
      case y =>
    }
  }
}

class CoqStepNotifier extends CoqCallback {
  var err : Boolean = false
  var test : Option[(Int) => Boolean] = None
  var later : Option[() => Unit] = None
  var walker : () => Unit = CoqStepAction.doit
  var undo : Boolean = false

  import org.eclipse.swt.widgets.Display

  override def dispatch (x : CoqResponse) : Unit = {
    x match {
      case CoqError(m) => err = true
      case CoqUserInterrupt() => err = true
      case CoqShellReady(monoton, tokens) =>
        if (! err) {
          if (test.isDefined && test.get(DocumentState.position)) {
            fini
            if (undo)
              Display.getDefault.syncExec(
                new Runnable() {
                  def run() = CoqStepUntilAction.doit
                });
          } else if (monoton || undo) {
            walker()
            val drops = DocumentState.position + DocumentState.sendlen
            if (drops >= DocumentState.content.length || CoqTop.findNextCommand(DocumentState.content.drop(drops)) == -1)
              if (! undo) {
                Console.println("in drops >= or -1 case")
                fini
              }
          } else
            fini
        } else
          fini
      case x => //Console.println("got something, try again player 1 " + x)
    }
  }

  def fini () : Unit = {
    PrintActor.deregister(this)
    later match {
      case Some(x) => x()
      case None =>
    }
    EclipseBoilerPlate.multistep = false
    EclipseBoilerPlate.finishedProgress
  }
}

object CoqOutputDispatcher extends CoqCallback {
  import org.eclipse.swt.widgets.Display

  var goalviewer : GoalViewer = null

  override def dispatch (x : CoqResponse) : Unit = {
    //Console.println("received in dispatch " + x)
    x match {
      case CoqShellReady(monoton, token) =>
        EclipseBoilerPlate.finishedProgress
        if (monoton)
          EclipseBoilerPlate.unmark
        ActionDisabler.enableMaybe
      case CoqGoal(n, goals) =>
        //Console.println("outputdispatcher, n is " + n + ", goals:\n" + goals)
        val (hy, res) = goals.splitAt(goals.findIndexOf(_.contains("======")))
        val ht = if (hy.length > 0) hy.reduceLeft(_ + "\n" + _) else ""
        val subd = res.findIndexOf(_.contains("subgoal "))
        val (g, r) = if (subd > 0) res.splitAt(subd) else (res, List[String]())
        val gt = if (g.length > 1) g.drop(1).reduceLeft(_ + "\n" + _) else ""
        val ot = if (r.length > 0) {
          val r2 = r.map(x => { if (x.contains("subgoal ")) x.drop(1) else x })
          r2.reduceLeft(_ + "\n" + _)
        } else ""
        goalviewer.writeGoal(ht, gt, ot)
      case CoqProofCompleted() => goalviewer.writeGoal("Proof completed", "", "")
      case CoqError(msg) =>
        //TODO: what if Error not found, should come up with a sensible message anyways!
        val ps = msg.drop(msg.findIndexOf(_.startsWith("Error")))
        EclipseBoilerPlate.mark(ps.reduceLeft(_ + " " + _))
      case x => EclipseConsole.out.println("received: " + x)
    }
  }
}

