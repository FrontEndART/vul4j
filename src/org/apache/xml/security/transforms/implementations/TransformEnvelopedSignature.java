/*
 * The Apache Software License, Version 1.1
 *
 *
 * Copyright (c) 1999 The Apache Software Foundation.  All rights
 * reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in
 *    the documentation and/or other materials provided with the
 *    distribution.
 *
 * 3. The end-user documentation included with the redistribution,
 *    if any, must include the following acknowledgment:
 *       "This product includes software developed by the
 *        Apache Software Foundation (http://www.apache.org/)."
 *    Alternately, this acknowledgment may appear in the software itself,
 *    if and wherever such third-party acknowledgments normally appear.
 *
 * 4. The names "<WebSig>" and "Apache Software Foundation" must
 *    not be used to endorse or promote products derived from this
 *    software without prior written permission. For written
 *    permission, please contact apache@apache.org.
 *
 * 5. Products derived from this software may not be called "Apache",
 *    nor may "Apache" appear in their name, without prior written
 *    permission of the Apache Software Foundation.
 *
 * THIS SOFTWARE IS PROVIDED ``AS IS'' AND ANY EXPRESSED OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED.  IN NO EVENT SHALL THE APACHE SOFTWARE FOUNDATION OR
 * ITS CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF
 * USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT
 * OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation and was
 * originally based on software copyright (c) 2001, Institute for
 * Data Communications Systems, <http://www.nue.et-inf.uni-siegen.de/>.
 * The development of this software was partly funded by the European
 * Commission in the <WebSig> project in the ISIS Programme.
 * For more information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 */
package org.apache.xml.security.transforms.implementations;



import java.io.IOException;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;
import org.apache.xml.security.c14n.*;
import org.apache.xml.security.c14n.helper.XPathContainer;
import org.apache.xml.security.exceptions.*;
import org.apache.xml.security.signature.XMLSignatureInput;
import org.apache.xml.security.transforms.*;
import org.apache.xml.security.utils.*;
import org.apache.xml.utils.PrefixResolver;
import org.apache.xml.utils.PrefixResolverDefault;
import org.apache.xpath.CachedXPathAPI;
import org.apache.xpath.XPathContext;
import org.apache.xpath.XPath;
import org.apache.xpath.objects.XObject;
import org.w3c.dom.*;
import org.xml.sax.SAXException;
import org.apache.xml.dtm.DTMManager;


/**
 * Implements the <CODE>http://www.w3.org/2000/09/xmldsig#enveloped-signature</CODE>
 * transform.
 *
 * @author Christian Geuer-Pollmann
 */
public class TransformEnvelopedSignature extends TransformSpi {

   /** {@link org.apache.log4j} logging facility */
   static org.apache.log4j.Category cat =
      org.apache.log4j.Category
         .getInstance(TransformEnvelopedSignature.class.getName());

   /** Field implementedTransformURI */
   public static final String implementedTransformURI =
      Transforms.TRANSFORM_ENVELOPED_SIGNATURE;

   //J-
   public boolean wantsOctetStream ()   { return true; }
   public boolean wantsNodeSet ()       { return true; }
   public boolean returnsOctetStream () { return true; }
   public boolean returnsNodeSet ()     { return false; }
   //J+

   /**
    * Method engineGetURI
    *
    * @return
    */
   protected String engineGetURI() {
      return this.implementedTransformURI;
   }

   /**
    * This transform performs the Enveloped-Signature-Transform by
    *
    * @param input
    * @return
    * @throws TransformationException
    */
   protected XMLSignatureInput enginePerformTransform(XMLSignatureInput input)
           throws TransformationException {

      try {

         /**
          * If the actual input is an octet stream, then the application MUST
          * convert the octet stream to an XPath node-set suitable for use by
          * Canonical XML with Comments. (A subsequent application of the
          * REQUIRED Canonical XML algorithm would strip away these comments.)
          *
          * ...
          *
          * The evaluation of this expression includes all of the document's nodes
          * (including comments) in the node-set representing the octet stream.
          */
         if (input.isOctetStream()) {
            input.setNodesetXPath(Canonicalizer.XPATH_C14N_WITH_COMMENTS);
         }

         NodeList inputNodes = input.getNodeSet();

         if (inputNodes.getLength() == 0) {
            Object exArgs[] = { "input node set contains no nodes" };

            throw new TransformationException("generic.EmptyMessage", exArgs);
         }

         HelperNodeList resultNodes = new HelperNodeList();

         /**
          * compile XPath for evaluation; this is taken from {@link XPathAPI#eval}
          */
         Document doc = XMLUtils.getOwnerDocument(inputNodes.item(0));
         Element nscontext = XMLUtils.createDSctx(doc, "ds",
                                                  Constants.SignatureSpecNS);
         PrefixResolverDefault prefixResolver =
            new PrefixResolverDefault(nscontext);
         XPath xpath = new XPath("count(ancestor-or-self::ds:Signature | "
                                 + "here()/ancestor::ds:Signature[1]) > "
                                 + "count(ancestor-or-self::ds:Signature)",
                                 null, prefixResolver, XPath.SELECT, null);

         // now set the xPathContext with the XPath Text node as owner document;
         // this is important for the here() function; as defined by the spec,
         // the here function will return an empty nodeset because we have no
         // node that bears our xpath expression.
         //
         // Well, the Algorithm Attribute does not contain the XPath, but for
         // our purpose to get the here() function running, it works
         Node algorithmAttr =
            this._transformObject.getElement()
               .getAttributeNode(Constants._ATT_ALGORITHM);
         FuncHereContext funcHereCtx = new FuncHereContext(algorithmAttr,
                                          input.getXPathContext());

         funcHereCtx.setNamespaceContext(prefixResolver);
         cat.debug("Selected " + algorithmAttr
                   + " attribute as owner for FuncHereContext");

         /*
         // create a DTMIterator from the input node set
         // (does not work in Xalan 2.2.D7) ;-(
         org.apache.xpath.NodeSetDTM dtmIterator =
            new org.apache.xpath.NodeSetDTM(inputNodes, funcHereCtx);

         funcHereCtx.pushContextNodeList(dtmIterator);
         */
         DTMManager dtmManager = input.getXPathContext().getDTMManager();
         org.apache.xpath.NodeSetDTM dtmIterator =
            new org.apache.xpath.NodeSetDTM(dtmManager);

         for (int i = 0; i < inputNodes.getLength(); i++) {
            dtmIterator
               .addNode(dtmManager.getDTMHandleFromNode(inputNodes.item(i)));
         }

         funcHereCtx.pushContextNodeList(dtmIterator);

         for (int i = 0; i < inputNodes.getLength(); i++) {
            Node currentContextNode = inputNodes.item(i);
            XObject value = xpath.execute((XPathContext) funcHereCtx,
                                          currentContextNode, prefixResolver);

            if (value.getType() != XObject.CLASS_BOOLEAN) {
               throw new TransformerException(
                  "The XPath.execute() method did not return an XBoolean");
            }

            if (value.bool()) {
               cat.debug("added ("
                         + ((currentContextNode.getNamespaceURI() != null)
                            ? "{" + currentContextNode.getNamespaceURI() + "} "
                            : "") + currentContextNode.getNodeName() + ")");
               resultNodes.appendChild(currentContextNode);
            } else {
               cat.debug("not added ("
                         + ((currentContextNode.getNamespaceURI() != null)
                            ? "{" + currentContextNode.getNamespaceURI() + "} "
                            : "") + currentContextNode.getNodeName() + ")");
            }
         }

         XMLSignatureInput result = new XMLSignatureInput(resultNodes,
                                       input.getXPathContext());

         cat.debug(
            "TransformsEnvelopedSignature finished processing and returns "
            + resultNodes.getLength() + " nodes");

         return result;
      } catch (IOException ex) {
         throw new TransformationException("empty", ex);
      } catch (SAXException ex) {
         throw new TransformationException("empty", ex);
      } catch (TransformerException ex) {
         throw new TransformationException("empty", ex);
      } catch (ParserConfigurationException ex) {
         throw new TransformationException("empty", ex);
      } catch (CanonicalizationException ex) {
         throw new TransformationException("empty", ex);
      } catch (InvalidCanonicalizerException ex) {
         throw new TransformationException("empty", ex);
      }
   }
}
