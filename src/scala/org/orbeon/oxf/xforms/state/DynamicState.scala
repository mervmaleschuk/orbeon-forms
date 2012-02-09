/**
 *  Copyright (C) 2012 Orbeon, Inc.
 *
 *  This program is free software; you can redistribute it and/or modify it under the terms of the
 *  GNU Lesser General Public License as published by the Free Software Foundation; either version
 *  2.1 of the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *  See the GNU Lesser General Public License for more details.
 *
 *  The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.xforms.state

import collection.JavaConverters._

import sbinary.Operations._
import Operations._
import XFormsProtocols._

import org.orbeon.oxf.util.URLRewriterUtils.PathMatcher
import collection.mutable.Buffer
import org.orbeon.oxf.xforms.control.XFormsControl
import org.orbeon.oxf.xforms.state.DynamicState.Control
import org.orbeon.oxf.xforms._
import org.orbeon.oxf.xml.dom4j.Dom4jUtils
import org.orbeon.oxf.xml.{TransformerUtils, SAXStore}

// Immutable representation of the dynamic state
case class DynamicState(
    uuid: String,
    sequence: Long,
    deploymentType: Option[String],
    requestContextPath: Option[String],
    requestPath: Option[String],
    containerType: Option[String],
    containerNamespace: Option[String],
    pathMatchers: Seq[Byte],
    pendingUploads: Seq[Byte],
    annotatedTemplate: Seq[Byte],
    lastAjaxResponse: Seq[Byte],
    instances: Seq[Byte],
    controls: Seq[Byte]
) {
    /* For testing only
    locally {
        val bytes = toByteSeq(this)
        println("  size: " + bytes.size)
        println("   versionedPathMatchers: " + pathMatchers.size)
        println("   pendingUploads: " + pendingUploads.size)
        println("   instances: " + instances.size)
        println("   controls: " + controls.size)
        println("   annotatedTemplate: " + annotatedTemplate.size)
        println("   lastAjaxResponse: " + lastAjaxResponse.size)
        
        val xxx0 = decodePathMatchersJava.toArray
        val xxx1 = decodePendingUploadsJava
        val xxx3 = decodeControlsJava
        val xxx2 = decodeInstancesJava.toArray
        val xxx4 = decodeAnnotatedTemplateJava
        val xxx5 = decodeLastAjaxResponseJava

        val deserialized = fromByteSeq[DynamicState](bytes)
        assert(this == deserialized)
    }
    */

    def decodePathMatchers = fromByteSeq[List[PathMatcher]](pathMatchers)
    def decodePendingUploads = fromByteSeq[Set[String]](pendingUploads)
    def decodeAnnotatedTemplate = fromByteSeq[Option[SAXStore]](annotatedTemplate)
    def decodeLastAjaxResponse = fromByteSeq[Option[SAXStore]](lastAjaxResponse)
    def decodeInstances = fromByteSeq[List[XFormsInstance]](instances)
    def decodeControls = fromByteSeq[List[Control]](controls) map (c ⇒ (c.effectiveId, c.keyValues))

    // For Java callers
    def decodeDeploymentTypeJava = deploymentType.orNull
    def decodeRequestContextPathJava = requestContextPath.orNull
    def decodeRequestPathJava = requestPath.orNull
    def decodeContainerTypeJava = containerType.orNull
    def decodeContainerNamespaceJava = containerNamespace.orNull
    def decodePathMatchersJava = decodePathMatchers.asJava
    def decodePendingUploadsJava = decodePendingUploads.asJava
    def decodeAnnotatedTemplateJava = decodeAnnotatedTemplate.orNull
    def decodeLastAjaxResponseJava = decodeLastAjaxResponse.orNull
    def decodeInstancesJava = decodeInstances.asJava
    def decodeControlsJava = decodeControls.toMap mapValues (_.asJava) asJava
    
    // For tests only
    def copyUpdateSequence(sequence: Int) = copy(sequence = sequence)

    // Encode to a string representation
    def encodeToString(compress: Boolean, isForceEncryption: Boolean): String =
        XFormsUtils.encodeBytes(
            toByteArray(this),
            compress,
            if (isForceEncryption) XFormsProperties.getXFormsPassword else null
        )

    // Encode to an XML representation (as of 2012-02-05, used only by unit tests)
    def toXML = {
        val document = Dom4jUtils.createDocument
        val rootElement = document.addElement("dynamic-state")

        // Add UUIDs
        rootElement.addAttribute("uuid", uuid)
        rootElement.addAttribute("sequence", sequence.toString)

        // Add request information
        rootElement.addAttribute("deployment-type", deploymentType.orNull)
        rootElement.addAttribute("request-context-path", requestContextPath.orNull)
        rootElement.addAttribute("request-path", requestPath.orNull)
        rootElement.addAttribute("container-type", containerType.orNull)
        rootElement.addAttribute("container-namespace", containerNamespace.orNull)

        // Remember versioned paths
        if (decodePathMatchers.nonEmpty) {
            val matchersElement = rootElement.addElement("matchers")
            for (matcher ← decodePathMatchers)
                matchersElement.add(matcher.toXML)
        }

        // Add upload information
        if (decodePendingUploads.nonEmpty)
            rootElement.addAttribute("pending-uploads", decodePendingUploads mkString " ")

        // Serialize instances
        locally {
            val instances = decodeInstances
            if (instances.nonEmpty) {
                val instancesElement = rootElement.addElement("instances")
                instances foreach (i ⇒ instancesElement.add(i.toXML(! i.cache)))
            }
        }

        // Serialize controls
        locally {
            val controls = decodeControls
            if (controls.nonEmpty) {
                val controlsElement = rootElement.addElement("controls")
                controls foreach {
                    case (effectiveId, keyValues) ⇒
                        val controlElement = controlsElement.addElement("control")
                        controlElement.addAttribute("effective-id", effectiveId)
                        for ((k, v) ← keyValues)
                            controlElement.addAttribute(k, v)
                }
            }
        }

        // Template and Ajax response
        Seq(("template", decodeAnnotatedTemplate), ("response", decodeLastAjaxResponse)) collect {
            case (elementName, Some(saxStore)) ⇒
                val templateElement = rootElement.addElement(elementName)
                val document = TransformerUtils.saxStoreToDom4jDocument(saxStore)
                templateElement.add(document.getRootElement.detach())
        }

        document
    }
}

object DynamicState {

    // Create a DynamicState from a document
    def apply(document: XFormsContainingDocument): DynamicState = {

        // Create the dynamic state object. A snapshot of the state is taken, whereby mutable parts of the state, such
        // as instances, controls, HTML template, Ajax response, are first serialized to Seq[Byte].
        // We could serialize everything right away to a Seq[Byte] instead of a DynamicState instance, but in the
        // scenario where the state is put in cache, then retrieved a bit later without having been pushed to external
        // storage, this would be a waste.
        DynamicState(
            document.getUUID,
            document.getSequence,
            Option(document.getDeploymentType) map (_.toString),
            Option(document.getRequestContextPath),
            Option(document.getRequestPath),
            Option(document.getContainerType),
            Option(document.getContainerNamespace),
            toByteSeq(document.getVersionedPathMatchers.asScala.toList),
            toByteSeq(document.getPendingUploads.asScala.toSet),
            toByteSeq(Option(document.getAnnotatedTemplate)),
            toByteSeq(Option(document.getLastAjaxResponse)),
            toByteSeq(document.getAllModels.asScala flatMap (_.getInstances.asScala) filter (_.mustSerialize) toList),
            toByteSeq(getControlsToSerialize(document).toList)
        )
    }

    // Create a DynamicState from an encoded string representation
    def apply(encoded: String): DynamicState = {
        val bytes = XFormsUtils.decodeBytes(encoded, XFormsProperties.getXFormsPassword)
        fromByteArray[DynamicState](bytes)
    }

    // Minimal representation of a serialized control
    case class Control(effectiveId: String, keyValues: Map[String, String])

    // Serialize relevant controls that have data
    // NOTE: As of 2012-02-02, only repeat, switch and dialogs controls serialize state. The state of all the other
    // controls is rebuilt from model data. This way we minimize the size of serialized controls.
    private def getControlsToSerialize(document: XFormsContainingDocument): Seq[Control] = {
        val result = Buffer[Control]()

        // Gather relevant control
        document.getControls.visitAllControls(new XFormsControls.XFormsControlVisitorAdapter() {
            override def startVisitControl(control: XFormsControl) = {
                if (control.isRelevant) { // don't serialize anything for non-relevant controls
                    Option(control.serializeLocal.asScala) filter (_.nonEmpty) foreach {
                        nameValues ⇒ result += Control(control.getEffectiveId, nameValues.toMap)
                    }
                }
                true
            }
        })

        result
    }

    // Encode the given document to a string representation
    def encodeDocumentToString(document: XFormsContainingDocument, compress: Boolean, isForceEncryption: Boolean): String =
        DynamicState(document).encodeToString(compress, isForceEncryption || XFormsProperties.isClientStateHandling(document))
}