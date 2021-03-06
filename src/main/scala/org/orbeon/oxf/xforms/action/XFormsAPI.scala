/**
 * Copyright (C) 2011 Orbeon, Inc.
 *
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version
 * 2.1 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU Lesser General Public License for more details.
 *
 * The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.xforms.action

import org.orbeon.oxf.xforms.action.actions._
import collection.JavaConverters._
import org.orbeon.saxon.om._
import java.util.{List ⇒ JList}
import org.orbeon.scaxon.XML._
import org.w3c.dom.Node.{ELEMENT_NODE, ATTRIBUTE_NODE}
import org.orbeon.oxf.xforms.xbl.XBLContainer

import org.orbeon.oxf.util.DynamicVariable
import org.orbeon.oxf.xforms.model.DataModel
import org.dom4j.QName

import org.orbeon.oxf.xforms.{XFormsModel, XFormsContainingDocument}
import org.orbeon.oxf.xforms.event.{XFormsEventTarget, Dispatch, XFormsEvent}
import org.orbeon.oxf.xforms.submission.XFormsModelSubmission
import org.orbeon.oxf.xforms.event.events.{XFormsSubmitDoneEvent, XFormsSubmitErrorEvent, XFormsSubmitEvent}
import org.orbeon.oxf.xforms.event.XFormsEvent._
import org.orbeon.oxf.xforms.control.controls.{XXFormsDialogControl, XFormsCaseControl}
import util.Try

object XFormsAPI {

    // Dynamically set context
    private val containingDocumentDyn = new DynamicVariable[XFormsContainingDocument]
    private val actionInterpreterDyn  = new DynamicVariable[XFormsActionInterpreter]

    // Every block of action must be run within this
    def withScalaAction(interpreter: XFormsActionInterpreter)(body: ⇒ Any) {
        actionInterpreterDyn.withValue(interpreter) {
            body
        }
    }

    // For Java callers
    def withContainingDocumentJava(containingDocument: XFormsContainingDocument, runnable: Runnable) =
        withContainingDocument(containingDocument) {
            runnable.run()
        }

    // Every block of action must be run within this
    def withContainingDocument(containingDocument: XFormsContainingDocument)(body: ⇒ Any) {
        containingDocumentDyn.withValue(containingDocument) {
            body
        }
    }

    // Return the action interpreter
    def actionInterpreter = { assert(actionInterpreterDyn.value.isDefined); actionInterpreterDyn.value.get }

    // Return the containing document
    def containingDocument = { assert(containingDocumentDyn.value.isDefined); containingDocumentDyn.value.get }

    // xf:setvalue
    // @return the node whose value was set, if any
    def setvalue(ref: Seq[NodeInfo], value: String) = {
        if (ref nonEmpty) {
            val nodeInfo = ref.head

            def onSuccess(oldValue: String): Unit =
                for {
                    action ← actionInterpreterDyn.value
                    containingDocument = action.containingDocument
                    indentedLogger = action.indentedLogger
                } yield
                    DataModel.logAndNotifyValueChange(containingDocument, indentedLogger, "scala setvalue", nodeInfo, oldValue, value, isCalculate = false)

            DataModel.setValueIfChanged(nodeInfo, value, onSuccess)

            Some(nodeInfo)
        } else
            None
    }

    // xf:setindex
    // @return:
    //
    // - None        if the control is not found
    // - Some(0)     if the control is non-relevant or doesn't have any iterations
    // - Some(index) otherwise, where index is the control's new index
    def setindex(repeatStaticId: String, index: Int) =
        actionInterpreterDyn.value map
            { interpreter ⇒ XFormsSetindexAction.executeSetindexAction(interpreter, interpreter.outerActionElement, repeatStaticId, index) } collect
                { case newIndex if newIndex >= 0 ⇒ newIndex }

    // xf:insert
    // @return the inserted nodes
    def insert[T <: Item](origin: Seq[T], into: Seq[NodeInfo] = Seq(), after: Seq[NodeInfo] = Seq(), before: Seq[NodeInfo] = Seq(), doDispatch: Boolean = true): Seq[T] = {

        if (origin.nonEmpty && (into.nonEmpty || after.nonEmpty || before.nonEmpty)) {
            val action = actionInterpreterDyn.value

            val (positionAttribute, collectionToUpdate) =
                if (before.nonEmpty)
                    ("before", before)
                else
                    ("after", after)

            XFormsInsertAction.doInsert(
                action map (_.containingDocument) orNull,
                action map (_.indentedLogger) orNull,
                positionAttribute,
                collectionToUpdate.asJava,
                into.headOption.orNull,
                origin.asJava.asInstanceOf[JList[Item]], // dirty cast for Java, safe if doInsert() doesn't modify the list
                collectionToUpdate.size,
                true, // doClone
                doDispatch).asInstanceOf[JList[T]].asScala
        } else
            Seq()
    }

    // xf:delete
    def delete(ref: Seq[NodeInfo], doDispatch: Boolean = true): Seq[NodeInfo] = {

        val action = actionInterpreterDyn.value

        val deleteInfos = XFormsDeleteAction.doDelete(action map (_.containingDocument) orNull, action map (_.indentedLogger) orNull, ref.asJava, -1, doDispatch)
        deleteInfos.asScala map (_.nodeInfo)
    }

    // Rename an element or attribute node
    // NOTE: This should be implemented as a core XForms action (see also XQuery updates)
    def rename(nodeInfo: NodeInfo, oldName: String, newName: String) {

        require(nodeInfo ne null)
        require(Set(ELEMENT_NODE, ATTRIBUTE_NODE)(nodeInfo.getNodeKind.toShort))

        if (oldName != newName) {
            val newNodeInfo = nodeInfo.getNodeKind match {
                case ELEMENT_NODE ⇒ elementInfo(newName, (nodeInfo \@ @*) ++ (nodeInfo \ Node))
                case ATTRIBUTE_NODE ⇒  attributeInfo(newName, attValueOption(nodeInfo).get)
                case _ ⇒ throw new IllegalArgumentException
            }

            insert(into = nodeInfo parent *, after = nodeInfo, origin = newNodeInfo)
            delete(nodeInfo)
        }
    }

    // Move the given element before another element
    def moveElementBefore(element: NodeInfo, other: NodeInfo) = {
        val inserted = insert(into = element parent *, before = other, origin = element)
        delete(element)
        inserted.head
    }

    // Move the given element after another element
    def moveElementAfter(element: NodeInfo, other: NodeInfo) = {
        val inserted = insert(into = element parent *, after = other, origin = element)
        delete(element)
        inserted.head
    }

    // Move the given element into another element as the last element
    def moveElementIntoAsLast(element: NodeInfo, other: NodeInfo) = {
        val inserted = insert(into = other, after = other \ *, origin = element)
        delete(element)
        inserted.head
    }

    // Set an attribute value, creating it if missing, updating it if present
    // NOTE: This should be implemented as an optimization of the XForms insert action.
    // @return the new or existing attribute node
    // NOTE: Would be nice to return attribute (new or existing), but doInsert() is not always able to wrap the inserted
    // nodes.
    def ensureAttribute(element: NodeInfo, attName: QName, value: String): Unit =
        element \@ attName match {
            case Seq()        ⇒ insert(into = element, origin = attributeInfo(attName, value))
            case Seq(att, _*) ⇒ setvalue(att, value)
        }

    // Return an instance's root element in the current action context
    def instanceRoot(staticId: String, searchAncestors: Boolean = false): Option[NodeInfo] = {

        val ai = actionInterpreter

        def ancestorXBLContainers = {
            def recurse(container: XBLContainer): List[XBLContainer] = container :: (container.getParentXBLContainer match {
                case parent: XBLContainer ⇒ recurse(parent)
                case _ ⇒ Nil
            })

            recurse(ai.container)
        }

        val containersToSearch =
            if (searchAncestors) ancestorXBLContainers else List(ai.container)

        containersToSearch flatMap
            (_.findInstance(staticId)) find
                (_ ne null) map
                    (_.rootElement)
    }

    // Return an instance within a top-level model
    def topLevelInstance(modelId: String, instanceId: String) =
        topLevelModel(modelId) flatMap (m ⇒ Option(m.getInstance(instanceId)))

    // Return a top-level model by static id
    // NOTE: This search is not very efficient, but this allows mocking in tests, where getObjectByEffectiveId causes issues
    // 2013-04-03: Unsure if we still need this for mocking
    def topLevelModel(modelId: String) =
        containingDocument.models find (_.getId == modelId)
    
    def context[T](xpath: String)(body: ⇒ T): T = ???
    def context[T](item: Item)(body: ⇒ T): T = ???
    def event[T](attributeName: String): Seq[Item] = ???

    // The xf:dispatch action
    def dispatch(
            name: String,
            targetId: String,
            bubbles: Boolean = true,
            cancelable: Boolean = true,
            properties: XFormsEvent.PropertyGetter = XFormsEvent.EmptyGetter,
            delay: Int = 0,
            showProgress: Boolean = true,
            progressMessage: String = null): Unit = {

        val target = containingDocument.getObjectByEffectiveId(targetId).asInstanceOf[XFormsEventTarget]

        XFormsDispatchAction.dispatch(
            name,
            target,
            bubbles,
            cancelable,
            properties,
            delay,
            showProgress,
            progressMessage
        )
    }

    private val SubmitEvents = Seq("xforms-submit-done", "xforms-submit-error")

    // The xf:send action
    // Send the given submission and applies the body with the resulting event if the submission completed
    def send[T](submissionId: String, properties: PropertyGetter = EmptyGetter)(body: XFormsEvent ⇒ T): Option[T] = {

        val submission = containingDocument.getObjectByEffectiveId(submissionId).asInstanceOf[XFormsModelSubmission]

        var result: Option[Try[T]] = None

        // Listener runs right away but stores the Try
        val listener: Dispatch.EventListener = { e ⇒
            result = Some(Try(body(e)))
        }

        // Add both listeners
        SubmitEvents foreach (submission.addListener(_, listener))

        // Dispatch and make sure the listeners are removed
        try Dispatch.dispatchEvent(new XFormsSubmitEvent(submission, properties))
        finally SubmitEvents foreach (submission.removeListener(_, listener))

        // - If the dispatch completed successfully and the submission started, it *should* have completed with either
        //   `xforms-submit-done` or `xforms-submit-error`. In this case, we have called `body(event)` and return
        //   `Option[T]` or throw an exception if `body(event)` failed.
        // - But in particular if the xforms-submit event got canceled, we might be in a situation where no
        //   xforms-submit-done or xforms-submit-error was dispatched. In this case, we return `None`.
        // - If the dispatch failed for other reasons, it might have thrown an exception, which is propagated.

        result map (_.get)
    }

    class SubmitException(e: XFormsSubmitErrorEvent) extends RuntimeException

    def sendThrowOnError(submissionId: String, properties: PropertyGetter = EmptyGetter): Option[XFormsSubmitDoneEvent] =
        XFormsAPI.send(submissionId, properties) {
            case done:  XFormsSubmitDoneEvent  ⇒ done
            case error: XFormsSubmitErrorEvent ⇒ throw new SubmitException(error)
        }

    // xf:toggle
    def toggle(caseId: String, deferred: Boolean = true): Unit = {
        val caseControl = containingDocument.getObjectByEffectiveId(caseId).asInstanceOf[XFormsCaseControl]
        XFormsToggleAction.toggle(caseControl, deferred)
    }

    // xf:rebuild
    def rebuild(modelId: String, deferred: Boolean = false): Unit = {
        val model = containingDocument.getObjectByEffectiveId(modelId).asInstanceOf[XFormsModel]
        RRRAction.rebuild(model, deferred)
    }

    // xf:revalidate
    def revalidate(modelId: String, deferred: Boolean = false): Unit = {
        val model = containingDocument.getObjectByEffectiveId(modelId).asInstanceOf[XFormsModel]
        RRRAction.revalidate(model, deferred)
    }

    // xf:recalculate
    def recalculate(modelId: String, deferred: Boolean = false, applyDefaults: Boolean = false): Unit = {
        val model = containingDocument.getObjectByEffectiveId(modelId).asInstanceOf[XFormsModel]
        RRRAction.recalculate(model, deferred, applyDefaults)
    }

    // xf:refresh
    def refresh(modelId: String): Unit = {
        val model = containingDocument.getObjectByEffectiveId(modelId).asInstanceOf[XFormsModel]
        XFormsRefreshAction.refresh(model)
    }

    // xf:show
    def show(dialogId: String, properties: PropertyGetter = EmptyGetter): Unit = {
        val dialogControl = containingDocument.getObjectByEffectiveId(dialogId).asInstanceOf[XXFormsDialogControl]
        XXFormsShowAction.showDialog(dialogControl, properties = properties)
    }

    // xf:load
    def load(url: String, target: Option[String] = None, progress: Boolean = true): Unit = {
        XFormsLoadAction.resolveStoreLoadValue(containingDocument, null, true, url, target.orNull, null, false, false)
    }
}