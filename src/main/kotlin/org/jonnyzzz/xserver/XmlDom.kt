package org.jonnyzzz.xserver

import org.w3c.dom.Document
import org.w3c.dom.Node
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

internal class XmlDom private constructor(
    private val document: Document,
    private val parent: Node,
) {
    fun comment(value: String) {
        parent.appendChild(document.createComment(value.replace("--", "- -")))
    }

    fun attributes(vararg attributes: Pair<String, Any?>) {
        (parent as? org.w3c.dom.Element)?.setAttributes(attributes.asList())
    }

    fun element(
        name: String,
        vararg attributes: Pair<String, Any?>,
        body: XmlDom.() -> Unit = {},
    ) {
        val element = document.createElement(name)
        element.setAttributes(attributes.asList())
        parent.appendChild(element)
        XmlDom(document, element).body()
    }

    fun svgElement(
        name: String,
        vararg attributes: Pair<String, Any?>,
        body: XmlDom.() -> Unit = {},
    ) {
        val element = document.createElementNS(SvgNamespace, name)
        element.setAttributes(attributes.asList())
        parent.appendChild(element)
        XmlDom(document, element).body()
    }

    fun text(value: String) {
        parent.appendChild(document.createTextNode(value))
    }

    private fun org.w3c.dom.Element.setAttributes(attributes: List<Pair<String, Any?>>) {
        for ((name, value) in attributes) {
            if (value != null) setAttribute(name, value.toString())
        }
    }

    companion object {
        private const val SvgNamespace = "http://www.w3.org/2000/svg"

        fun html(body: XmlDom.() -> Unit): String {
            val document = newDocument()
            document.appendChild(document.implementation.createDocumentType("html", "", ""))
            val root = document.createElement("html")
            document.appendChild(root)
            XmlDom(document, root).body()
            return serialize(document, method = "html")
        }

        fun svg(body: XmlDom.() -> Unit): String {
            val document = newDocument()
            val root = document.createElementNS(SvgNamespace, "svg")
            document.appendChild(root)
            XmlDom(document, root).body()
            return serialize(document, method = "xml")
        }

        private fun newDocument(): Document =
            DocumentBuilderFactory
                .newInstance()
                .also { it.isNamespaceAware = true }
                .newDocumentBuilder()
                .newDocument()

        private fun serialize(document: Document, method: String): String {
            val writer = StringWriter()
            TransformerFactory.newInstance().newTransformer().apply {
                setOutputProperty(OutputKeys.METHOD, method)
                setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
                setOutputProperty(OutputKeys.ENCODING, "UTF-8")
            }.transform(DOMSource(document), StreamResult(writer))
            return writer.toString()
        }
    }
}
