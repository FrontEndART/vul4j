/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.cxf.ws.security.wss4j.policyhandlers;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.security.auth.callback.CallbackHandler;
import javax.xml.crypto.dsig.Reference;
import javax.xml.namespace.QName;
import javax.xml.soap.SOAPException;
import javax.xml.soap.SOAPHeader;
import javax.xml.soap.SOAPMessage;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import org.apache.cxf.Bus;
import org.apache.cxf.binding.soap.SoapMessage;
import org.apache.cxf.common.WSDLConstants;
import org.apache.cxf.common.classloader.ClassLoaderUtils;
import org.apache.cxf.common.i18n.Message;
import org.apache.cxf.common.logging.LogUtils;
import org.apache.cxf.common.util.StringUtils;
import org.apache.cxf.endpoint.Endpoint;
import org.apache.cxf.helpers.CastUtils;
import org.apache.cxf.helpers.DOMUtils;
import org.apache.cxf.helpers.MapNamespaceContext;
import org.apache.cxf.interceptor.Fault;
import org.apache.cxf.message.MessageUtils;
import org.apache.cxf.resource.ResourceManager;
import org.apache.cxf.service.model.EndpointInfo;
import org.apache.cxf.ws.policy.AssertionInfo;
import org.apache.cxf.ws.policy.AssertionInfoMap;
import org.apache.cxf.ws.policy.PolicyConstants;
import org.apache.cxf.ws.policy.PolicyException;
import org.apache.cxf.ws.security.SecurityConstants;
import org.apache.cxf.ws.security.policy.SP12Constants;
import org.apache.cxf.ws.security.policy.SPConstants;
import org.apache.cxf.ws.security.policy.model.AsymmetricBinding;
import org.apache.cxf.ws.security.policy.model.Binding;
import org.apache.cxf.ws.security.policy.model.ContentEncryptedElements;
import org.apache.cxf.ws.security.policy.model.Header;
import org.apache.cxf.ws.security.policy.model.IssuedToken;
import org.apache.cxf.ws.security.policy.model.KeyValueToken;
import org.apache.cxf.ws.security.policy.model.Layout;
import org.apache.cxf.ws.security.policy.model.SamlToken;
import org.apache.cxf.ws.security.policy.model.SecureConversationToken;
import org.apache.cxf.ws.security.policy.model.SignedEncryptedElements;
import org.apache.cxf.ws.security.policy.model.SignedEncryptedParts;
import org.apache.cxf.ws.security.policy.model.SupportingToken;
import org.apache.cxf.ws.security.policy.model.SymmetricBinding;
import org.apache.cxf.ws.security.policy.model.Token;
import org.apache.cxf.ws.security.policy.model.TokenWrapper;
import org.apache.cxf.ws.security.policy.model.UsernameToken;
import org.apache.cxf.ws.security.policy.model.Wss10;
import org.apache.cxf.ws.security.policy.model.Wss11;
import org.apache.cxf.ws.security.policy.model.X509Token;
import org.apache.cxf.ws.security.tokenstore.MemoryTokenStore;
import org.apache.cxf.ws.security.tokenstore.SecurityToken;
import org.apache.cxf.ws.security.tokenstore.TokenStore;
import org.apache.neethi.Assertion;
import org.apache.ws.security.WSConstants;
import org.apache.ws.security.WSEncryptionPart;
import org.apache.ws.security.WSPasswordCallback;
import org.apache.ws.security.WSSConfig;
import org.apache.ws.security.WSSecurityEngineResult;
import org.apache.ws.security.WSSecurityException;
import org.apache.ws.security.WSUsernameTokenPrincipal;
import org.apache.ws.security.components.crypto.Crypto;
import org.apache.ws.security.components.crypto.CryptoFactory;
import org.apache.ws.security.conversation.ConversationConstants;
import org.apache.ws.security.conversation.ConversationException;
import org.apache.ws.security.handler.WSHandlerConstants;
import org.apache.ws.security.handler.WSHandlerResult;
import org.apache.ws.security.message.WSSecBase;
import org.apache.ws.security.message.WSSecDKSign;
import org.apache.ws.security.message.WSSecEncryptedKey;
import org.apache.ws.security.message.WSSecHeader;
import org.apache.ws.security.message.WSSecSignature;
import org.apache.ws.security.message.WSSecSignatureConfirmation;
import org.apache.ws.security.message.WSSecTimestamp;
import org.apache.ws.security.message.WSSecUsernameToken;
import org.apache.ws.security.message.token.SecurityTokenReference;
import org.apache.ws.security.saml.ext.AssertionWrapper;
import org.apache.ws.security.saml.ext.SAMLParms;
import org.apache.ws.security.util.WSSecurityUtil;

/**
 * 
 */
public abstract class AbstractBindingBuilder {
    public static final String CRYPTO_CACHE = "ws-security.crypto.cache";
    protected static final Logger LOG = LogUtils.getL7dLogger(AbstractBindingBuilder.class);
    
    protected SPConstants.ProtectionOrder protectionOrder = 
        SPConstants.ProtectionOrder.SignBeforeEncrypting;
    
    protected final WSSConfig wssConfig;
    protected SOAPMessage saaj;
    protected WSSecHeader secHeader;
    protected AssertionInfoMap aim;
    protected Binding binding;
    protected SoapMessage message;
    protected WSSecTimestamp timestampEl;
    protected String mainSigId;
    
    protected Set<String> encryptedTokensIdList = new HashSet<String>();

    protected Map<Token, Object> endEncSuppTokMap;
    protected Map<Token, Object> endSuppTokMap;
    protected Map<Token, Object> sgndEndEncSuppTokMap;
    protected Map<Token, Object> sgndEndSuppTokMap;
    
    protected List<byte[]> signatures = new ArrayList<byte[]>();

    Element lastSupportingTokenElement;
    Element lastEncryptedKeyElement;
    Element lastDerivedKeyElement;
    Element bottomUpElement;
    Element topDownElement;
    
    public AbstractBindingBuilder(
                           WSSConfig config,
                           Binding binding,
                           SOAPMessage saaj,
                           WSSecHeader secHeader,
                           AssertionInfoMap aim,
                           SoapMessage message) {
        this.wssConfig = config;
        this.binding = binding;
        this.aim = aim;
        this.secHeader = secHeader;
        this.saaj = saaj;
        this.message = message;
        message.getExchange().put(WSHandlerConstants.SEND_SIGV, signatures);
    }
    
    private void insertAfter(Element child, Element sib) {
        if (sib.getNextSibling() == null) {
            secHeader.getSecurityHeader().appendChild(child);
        } else {
            secHeader.getSecurityHeader().insertBefore(child, sib.getNextSibling());
        }
    }
    
    protected void addDerivedKeyElement(Element el) {
        if (lastDerivedKeyElement != null) {
            insertAfter(el, lastDerivedKeyElement);
        } else if (lastEncryptedKeyElement != null) {
            insertAfter(el, lastEncryptedKeyElement);
        } else if (topDownElement != null) {
            insertAfter(el, topDownElement);
        } else if (secHeader.getSecurityHeader().getFirstChild() != null) {
            secHeader.getSecurityHeader().insertBefore(
                el, secHeader.getSecurityHeader().getFirstChild()
            );
        } else {
            secHeader.getSecurityHeader().appendChild(el);
        }
        lastEncryptedKeyElement = el;
    }
    
    protected void addEncryptedKeyElement(Element el) {
        if (lastEncryptedKeyElement != null) {
            insertAfter(el, lastEncryptedKeyElement);
        } else if (lastDerivedKeyElement != null) {
            secHeader.getSecurityHeader().insertBefore(el, lastDerivedKeyElement);
        } else if (topDownElement != null) {
            insertAfter(el, topDownElement);
        } else if (secHeader.getSecurityHeader().getFirstChild() != null) {
            secHeader.getSecurityHeader().insertBefore(
                el, secHeader.getSecurityHeader().getFirstChild()
            );
        } else {
            secHeader.getSecurityHeader().appendChild(el);
        }
        lastEncryptedKeyElement = el;
    }
    
    protected void addSupportingElement(Element el) {
        if (lastSupportingTokenElement != null) {
            insertAfter(el, lastSupportingTokenElement);
        } else if (lastDerivedKeyElement != null) {
            insertAfter(el, lastDerivedKeyElement);
        } else if (lastEncryptedKeyElement != null) {
            insertAfter(el, lastEncryptedKeyElement);
        } else if (topDownElement != null) {
            insertAfter(el, topDownElement);
        } else if (bottomUpElement != null) {
            secHeader.getSecurityHeader().insertBefore(el, bottomUpElement);
        } else {
            secHeader.getSecurityHeader().appendChild(el);
        }
        lastSupportingTokenElement = el;
    }
    
    protected void insertBeforeBottomUp(Element el) {
        if (bottomUpElement == null) {
            secHeader.getSecurityHeader().appendChild(el);
        } else {
            secHeader.getSecurityHeader().insertBefore(el, bottomUpElement);
        }
        bottomUpElement = el;
    }
    
    protected void addTopDownElement(Element el) {
        if (topDownElement == null) {
            if (secHeader.getSecurityHeader().getFirstChild() == null) {
                secHeader.getSecurityHeader().appendChild(el);
            } else {
                secHeader.getSecurityHeader().insertBefore(
                    el, secHeader.getSecurityHeader().getFirstChild()
                );
            }
        } else {
            insertAfter(el, topDownElement);
        }
        topDownElement = el;
    }
    
    protected boolean isRequestor() {
        return MessageUtils.isRequestor(message);
    }
    
    protected void policyNotAsserted(Assertion assertion, Exception reason) {
        if (assertion == null) {
            return;
        }
        LOG.log(Level.FINE, "Not asserting " + assertion.getName() + ": " + reason);
        Collection<AssertionInfo> ais;
        ais = aim.get(assertion.getName());
        if (ais != null) {
            for (AssertionInfo ai : ais) {
                if (ai.getAssertion() == assertion) {
                    ai.setNotAsserted(reason.getMessage());
                }
            }
        }
        throw new PolicyException(reason);
    }
    
    protected void policyNotAsserted(Assertion assertion, String reason) {
        if (assertion == null) {
            return;
        }
        LOG.log(Level.FINE, "Not asserting " + assertion.getName() + ": " + reason);
        Collection<AssertionInfo> ais;
        ais = aim.get(assertion.getName());
        if (ais != null) {
            for (AssertionInfo ai : ais) {
                if (ai.getAssertion() == assertion) {
                    ai.setNotAsserted(reason);
                }
            }
        }
        if (!assertion.isOptional()) {
            throw new PolicyException(new Message(reason, LOG));
        }
    }
    
    protected void policyAsserted(Assertion assertion) {
        if (assertion == null) {
            return;
        }
        LOG.log(Level.FINE, "Asserting " + assertion.getName());
        Collection<AssertionInfo> ais;
        ais = aim.get(assertion.getName());
        if (ais != null) {
            for (AssertionInfo ai : ais) {
                if (ai.getAssertion() == assertion) {
                    ai.setAsserted(true);
                }
            }
        }
    }
    
    protected void policyAsserted(QName n) {
        Collection<AssertionInfo> ais = aim.getAssertionInfo(n);
        if (ais != null && !ais.isEmpty()) {
            for (AssertionInfo ai : ais) {
                ai.setAsserted(true);
            }
        }
    }
    
    protected Collection<Assertion> findAndAssertPolicy(QName n) {
        Collection<AssertionInfo> ais = aim.getAssertionInfo(n);
        if (ais != null && !ais.isEmpty()) {
            List<Assertion> p = new ArrayList<Assertion>(ais.size());
            for (AssertionInfo ai : ais) {
                ai.setAsserted(true);
                p.add(ai.getAssertion());
            }
            return p;
        }
        return null;
    } 
    
    protected final Map<Object, Crypto> getCryptoCache() {
        EndpointInfo info = message.getExchange().get(Endpoint.class).getEndpointInfo();
        synchronized (info) {
            Map<Object, Crypto> o = 
                CastUtils.cast((Map<?, ?>)message.getContextualProperty(CRYPTO_CACHE));
            if (o == null) {
                o = new ConcurrentHashMap<Object, Crypto>();
                info.setProperty(CRYPTO_CACHE, o);
            }
            return o;
        }
    }
    
    protected final TokenStore getTokenStore() {
        EndpointInfo info = message.getExchange().get(Endpoint.class).getEndpointInfo();
        synchronized (info) {
            TokenStore tokenStore = 
                (TokenStore)message.getContextualProperty(TokenStore.class.getName());
            if (tokenStore == null) {
                tokenStore = (TokenStore)info.getProperty(TokenStore.class.getName());
            }
            if (tokenStore == null) {
                tokenStore = new MemoryTokenStore();
                info.setProperty(TokenStore.class.getName(), tokenStore);
            }
            return tokenStore;
        }
    }
    
    protected WSSecTimestamp createTimestamp() {
        Collection<AssertionInfo> ais;
        ais = aim.get(SP12Constants.INCLUDE_TIMESTAMP);
        if (ais != null) {
            Object o = message.getContextualProperty(SecurityConstants.TIMESTAMP_TTL);
            int ttl = 300;  //default is 300 seconds
            if (o instanceof Number) {
                ttl = ((Number)o).intValue();
            } else if (o instanceof String) {
                ttl = Integer.parseInt((String)o);
            }
            if (ttl <= 0) {
                ttl = 300;
            }
            timestampEl = new WSSecTimestamp(wssConfig);
            timestampEl.setWsConfig(wssConfig);
            timestampEl.setTimeToLive(ttl);
            timestampEl.prepare(saaj.getSOAPPart());
            for (AssertionInfo ai : ais) {
                ai.setAsserted(true);
            }                    
        }
        return timestampEl;
    }
    
    protected WSSecTimestamp handleLayout(WSSecTimestamp timestamp) {
        Collection<AssertionInfo> ais;
        ais = aim.get(SP12Constants.LAYOUT);
        if (ais != null) {
            for (AssertionInfo ai : ais) {
                Layout layout = (Layout)ai.getAssertion();
                ai.setAsserted(true);
                if (SPConstants.Layout.LaxTimestampLast == layout.getValue()) {
                    if (timestamp == null) {
                        ai.setNotAsserted(SPConstants.Layout.LaxTimestampLast + " requires a timestamp");
                    } else {
                        ai.setAsserted(true);
                        Element el = timestamp.getElement();
                        secHeader.getSecurityHeader().appendChild(el);
                        if (bottomUpElement == null) {
                            bottomUpElement = el;
                        }
                    }
                } else if (SPConstants.Layout.LaxTimestampFirst == layout.getValue()) {
                    if (timestamp == null) {
                        ai.setNotAsserted(SPConstants.Layout.LaxTimestampLast + " requires a timestamp");
                    } else {
                        addTopDownElement(timestampEl.getElement());
                    }
                } else if (timestampEl != null) {
                    addTopDownElement(timestampEl.getElement());
                }
            }                    
        } else if (timestampEl != null) {
            addTopDownElement(timestampEl.getElement());
        }
        return timestamp;
    }
    
    protected void assertSupportingTokens(Collection<Assertion> suppTokens) {
        if (suppTokens == null) {
            return;
        }
        for (Assertion pa : suppTokens) {
            if (pa instanceof SupportingToken) {
                for (Token token : ((SupportingToken)pa).getTokens()) {
                    this.policyAsserted(token);
                }        
            }
        }
    }
    
    protected Map<Token, Object> handleSupportingTokens(
        Collection<Assertion> tokens, 
        boolean endorse
    ) throws WSSecurityException {
        Map<Token, Object> ret = new HashMap<Token, Object>();
        if (tokens != null) {
            for (Assertion pa : tokens) {
                if (pa instanceof SupportingToken) {
                    handleSupportingTokens((SupportingToken)pa, endorse, ret);
                }
            }
        }
        return ret;
    }
    
    protected Map<Token, Object> handleSupportingTokens(
        SupportingToken suppTokens,
        boolean endorse
    ) throws WSSecurityException {
        return handleSupportingTokens(suppTokens, endorse, new HashMap<Token, Object>());
    }
    
    protected Map<Token, Object> handleSupportingTokens(
        SupportingToken suppTokens, 
        boolean endorse,
        Map<Token, Object> ret
    ) throws WSSecurityException {
        if (suppTokens == null) {
            return ret;
        }
        for (Token token : suppTokens.getTokens()) {
            if (token instanceof UsernameToken) {
                WSSecUsernameToken utBuilder = addUsernameToken((UsernameToken)token);
                if (utBuilder != null) {
                    utBuilder.prepare(saaj.getSOAPPart());
                    addSupportingElement(utBuilder.getUsernameTokenElement());
                    ret.put(token, utBuilder);
                    //WebLogic and WCF always encrypt these
                    //See:  http://e-docs.bea.com/wls/docs103/webserv_intro/interop.html
                    //encryptedTokensIdList.add(utBuilder.getId());
                    if (suppTokens.isEncryptedToken()
                        || MessageUtils.getContextualBoolean(message, 
                                                             SecurityConstants.ALWAYS_ENCRYPT_UT,
                                                             true)) {
                        encryptedTokensIdList.add(utBuilder.getId());
                    }
                }
            } else if (isRequestor() 
                && (token instanceof IssuedToken
                    || token instanceof SecureConversationToken)) {
                //ws-trust/ws-sc stuff.......
                SecurityToken secToken = getSecurityToken();
                if (secToken == null) {
                    policyNotAsserted(token, "Could not find IssuedToken");
                }
                addSupportingElement(cloneElement(secToken.getToken()));
        
                if (suppTokens.isEncryptedToken()) {
                    this.encryptedTokensIdList.add(secToken.getId());
                }
        
                if (secToken.getX509Certificate() == null) {   
                    //Add the extracted token
                    ret.put(token, new WSSecurityTokenHolder(wssConfig, secToken));
                } else {
                    WSSecSignature sig = new WSSecSignature(wssConfig);                    
                    sig.setX509Certificate(secToken.getX509Certificate());
                    sig.setCustomTokenId(secToken.getId());
                    sig.setKeyIdentifierType(WSConstants.CUSTOM_KEY_IDENTIFIER);
                    // TODO Add support for SAML2 here
                    if (secToken.getTokenType() == null) {
                        sig.setCustomTokenValueType(WSConstants.WSS_SAML_KI_VALUE_TYPE);
                    } else {
                        sig.setCustomTokenValueType(secToken.getTokenType());
                    }
                    sig.setSignatureAlgorithm(binding.getAlgorithmSuite().getAsymmetricSignature());
                    sig.setSigCanonicalization(binding.getAlgorithmSuite().getInclusiveC14n());
                    
                    Crypto crypto = secToken.getCrypto();
                    String uname = null;
                    try {
                        uname = crypto.getX509Identifier(secToken.getX509Certificate());
                    } catch (WSSecurityException e1) {
                        throw new Fault(e1);
                    }

                    String password = getPassword(uname, token, WSPasswordCallback.SIGNATURE);
                    if (password == null) {
                        password = "";
                    }
                    sig.setUserInfo(uname, password);
                    try {
                        sig.prepare(saaj.getSOAPPart(), secToken.getCrypto(), secHeader);
                    } catch (WSSecurityException e) {
                        throw new Fault(e);
                    }
                    
                    if (suppTokens.isEncryptedToken()) {
                        encryptedTokensIdList.add(secToken.getId());
                    }
                    ret.put(token, sig);                
                }

            } else if (token instanceof X509Token) {
                //We have to use a cert
                //Prepare X509 signature
                WSSecSignature sig = getSignatureBuilder(suppTokens, token, endorse);
                Element bstElem = sig.getBinarySecurityTokenElement();
                if (bstElem != null) {
                    sig.prependBSTElementToHeader(secHeader);
                }
                if (suppTokens.isEncryptedToken()) {
                    encryptedTokensIdList.add(sig.getBSTTokenId());
                }
                ret.put(token, sig);
            } else if (token instanceof KeyValueToken) {
                WSSecSignature sig = getSignatureBuilder(suppTokens, token, endorse);
                if (suppTokens.isEncryptedToken()) {
                    encryptedTokensIdList.add(sig.getBSTTokenId());
                }
                ret.put(token, sig);                
            } else if (token instanceof SamlToken) {
                AssertionWrapper assertionWrapper = addSamlToken((SamlToken)token);
                if (assertionWrapper != null) {
                    addSupportingElement(assertionWrapper.toDOM(saaj.getSOAPPart()));
                    ret.put(token, assertionWrapper);
                }
            }
        }
        return ret;
    }
    
    protected Element cloneElement(Element el) {
        return (Element)secHeader.getSecurityHeader().getOwnerDocument().importNode(el, true);
    }

    protected SecurityToken getSecurityToken() {
        SecurityToken st = (SecurityToken)message.getContextualProperty(SecurityConstants.TOKEN);
        if (st == null) {
            String id = (String)message.getContextualProperty(SecurityConstants.TOKEN_ID);
            if (id != null) {
                st = getTokenStore().getToken(id);
            }
        }
        getTokenStore().add(st);
        return st;
    }

    protected void addSignatureParts(Map<Token, Object> tokenMap,
                                       List<WSEncryptionPart> sigParts) {
        
        for (Map.Entry<Token, Object> entry : tokenMap.entrySet()) {
            
            Object tempTok = entry.getValue();
            WSEncryptionPart part = null;
            
            if (tempTok instanceof WSSecSignature) {
                WSSecSignature tempSig = (WSSecSignature) tempTok;
                SecurityTokenReference secRef = tempSig.getSecurityTokenReference();
                // TODO Add support for SAML2 here
                if ((WSConstants.WSS_SAML_KI_VALUE_TYPE).equals(secRef.getKeyIdentifierValueType())) {
                    
                    Element secRefElement = cloneElement(secRef.getElement());
                    addSupportingElement(secRefElement);
                               
                    part = new WSEncryptionPart("STRTransform", null, "Element");
                    part.setId(tempSig.getSecurityTokenReferenceURI());
                    part.setElement(secRefElement);
                } else {
                    if (tempSig.getBSTTokenId() != null) {
                        part = new WSEncryptionPart(tempSig.getBSTTokenId());
                        part.setElement(tempSig.getBinarySecurityTokenElement());
                    }
                }
            } else if (tempTok instanceof WSSecUsernameToken) {
                WSSecUsernameToken unt = (WSSecUsernameToken)tempTok;
                part = new WSEncryptionPart(unt.getId());
                part.setElement(unt.getUsernameTokenElement());
            } else if (tempTok instanceof AssertionWrapper) {
                AssertionWrapper assertionWrapper = (AssertionWrapper)tempTok;
                part = new WSEncryptionPart(assertionWrapper.getId());
                part.setElement(assertionWrapper.getElement());
                
                // TODO We only support using a KeyIdentifier for the moment
                SecurityTokenReference secRef = 
                    createSTRForSamlAssertion(assertionWrapper, false);
                addSupportingElement(secRef.getElement());
                part = new WSEncryptionPart("STRTransform", null, "Element");
                part.setId(secRef.getID());
                part.setElement(secRef.getElement());
            } else {
                policyNotAsserted(entry.getKey(), "UnsupportedTokenInSupportingToken: " + tempTok);  
            }
            if (part != null) {
                sigParts.add(part);
            }
        }
    }
    
    /**
     * Create a SecurityTokenReference to point to a SAML Assertion
     * @param assertion the SAML AssertionWrapper
     * @param useDirectReferenceToAssertion whether to refer directly to the assertion or not
     * @return a SecurityTokenReference to a SAML Assertion
     */
    private SecurityTokenReference createSTRForSamlAssertion(
        AssertionWrapper assertion,
        boolean useDirectReferenceToAssertion
    ) {
        Document doc = assertion.getElement().getOwnerDocument();
        SecurityTokenReference secRefSaml = new SecurityTokenReference(doc);
        String secRefID = wssConfig.getIdAllocator().createSecureId("STRSAMLId-", secRefSaml);
        secRefSaml.setID(secRefID);

        if (useDirectReferenceToAssertion) {
            org.apache.ws.security.message.token.Reference ref = 
                new org.apache.ws.security.message.token.Reference(doc);
            ref.setURI("#" + assertion.getId());
            if (assertion.getSaml1() != null) {
                ref.setValueType(WSConstants.WSS_SAML_KI_VALUE_TYPE);
                secRefSaml.addTokenType(WSConstants.WSS_SAML_TOKEN_TYPE);
            } else if (assertion.getSaml2() != null) {
                secRefSaml.addTokenType(WSConstants.WSS_SAML2_TOKEN_TYPE);
            }
            secRefSaml.setReference(ref);
        } else {
            Element keyId = doc.createElementNS(WSConstants.WSSE_NS, "wsse:KeyIdentifier");
            String valueType = null;
            if (assertion.getSaml1() != null) {
                valueType = WSConstants.WSS_SAML_KI_VALUE_TYPE;
                secRefSaml.addTokenType(WSConstants.WSS_SAML_TOKEN_TYPE);
            } else if (assertion.getSaml2() != null) {
                valueType = WSConstants.WSS_SAML2_KI_VALUE_TYPE;
                secRefSaml.addTokenType(WSConstants.WSS_SAML2_TOKEN_TYPE);
            }
            keyId.setAttributeNS(
                null, "ValueType", valueType
            );
            keyId.appendChild(doc.createTextNode(assertion.getId()));
            Element elem = secRefSaml.getElement();
            elem.appendChild(keyId);
        }
        return secRefSaml;
    }

    protected WSSecUsernameToken addUsernameToken(UsernameToken token) {
        AssertionInfo info = null;
        Collection<AssertionInfo> ais = aim.getAssertionInfo(token.getName());
        for (AssertionInfo ai : ais) {
            if (ai.getAssertion() == token) {
                info = ai;
                if (!isRequestor()) {
                    info.setAsserted(true);
                    return null;
                }
            }
        }
        
        String userName = (String)message.getContextualProperty(SecurityConstants.USERNAME);
        if (!StringUtils.isEmpty(userName)) {
            // If NoPassword property is set we don't need to set the password
            if (token.isNoPassword()) {
                WSSecUsernameToken utBuilder = new WSSecUsernameToken(wssConfig);
                utBuilder.setUserInfo(userName, null);
                utBuilder.setPasswordType(null);
                info.setAsserted(true);
                return utBuilder;
            }
            
            String password = (String)message.getContextualProperty(SecurityConstants.PASSWORD);
            if (StringUtils.isEmpty(password)) {
                password = getPassword(userName, token, WSPasswordCallback.USERNAME_TOKEN);
            }
            
            if (!StringUtils.isEmpty(password)) {
                //If the password is available then build the token
                WSSecUsernameToken utBuilder = new WSSecUsernameToken(wssConfig);
                if (token.isHashPassword()) {
                    utBuilder.setPasswordType(WSConstants.PASSWORD_DIGEST);  
                } else {
                    utBuilder.setPasswordType(WSConstants.PASSWORD_TEXT);
                }
                
                utBuilder.setUserInfo(userName, password);
                info.setAsserted(true);
                return utBuilder;
            } else {
                policyNotAsserted(token, "No username available");
            }
        } else {
            policyNotAsserted(token, "No username available");
        }
        return null;
    }
    
    protected AssertionWrapper addSamlToken(SamlToken token) throws WSSecurityException {
        AssertionInfo info = null;
        Collection<AssertionInfo> ais = aim.getAssertionInfo(token.getName());
        for (AssertionInfo ai : ais) {
            if (ai.getAssertion() == token) {
                info = ai;
                if (!isRequestor()) {
                    info.setAsserted(true);
                    return null;
                }
            }
        }
        
        //
        // Get the SAML CallbackHandler
        //
        Object o = message.getContextualProperty(SecurityConstants.SAML_CALLBACK_HANDLER);
    
        CallbackHandler handler = null;
        if (o instanceof CallbackHandler) {
            handler = (CallbackHandler)o;
        } else if (o instanceof String) {
            try {
                handler = (CallbackHandler)ClassLoaderUtils
                    .loadClass((String)o, this.getClass()).newInstance();
            } catch (Exception e) {
                handler = null;
            }
        }
        if (handler == null) {
            policyNotAsserted(token, "No SAML CallbackHandler available");
            return null;
        }
        
        SAMLParms samlParms = new SAMLParms();
        samlParms.setCallbackHandler(handler);
        info.setAsserted(true);
        return new AssertionWrapper(samlParms);
    }
    
    public String getPassword(String userName, Assertion info, int type) {
        //Then try to get the password from the given callback handler
        Object o = message.getContextualProperty(SecurityConstants.CALLBACK_HANDLER);
    
        CallbackHandler handler = null;
        if (o instanceof CallbackHandler) {
            handler = (CallbackHandler)o;
        } else if (o instanceof String) {
            try {
                handler = (CallbackHandler)ClassLoaderUtils
                    .loadClass((String)o, this.getClass()).newInstance();
            } catch (Exception e) {
                handler = null;
            }
        }
        if (handler == null) {
            policyNotAsserted(info, "No callback handler and no password available");
            return null;
        }
        
        WSPasswordCallback[] cb = {new WSPasswordCallback(userName, type)};
        try {
            handler.handle(cb);
        } catch (Exception e) {
            policyNotAsserted(info, e);
        }
        
        //get the password
        return cb[0].getPassword();
    }

    /**
     * Generates a wsu:Id attribute for the provided {@code Element} and returns the attribute value
     * or finds and returns the value of the attribute if it already exists.
     * 
     * @param element the {@code Element} to check/create the attribute on
     *
     * @return the generated or discovered wsu:Id attribute value
     */
    public String addWsuIdToElement(Element elem) {
        String id;
        
        //first try to get the Id attr
        Attr idAttr = elem.getAttributeNode("Id");
        if (idAttr == null) {
            //then try the wsu:Id value
            idAttr = elem.getAttributeNodeNS(PolicyConstants.WSU_NAMESPACE_URI, "Id");
        }
        
        if (idAttr != null) {
            id = idAttr.getValue();
        } else {
            //Add an id
            id = "Id-" + elem.hashCode();
            String pfx = elem.lookupPrefix(PolicyConstants.WSU_NAMESPACE_URI);
            boolean found = !StringUtils.isEmpty(pfx);
            int cnt = 0;
            while (StringUtils.isEmpty(pfx)) {
                pfx = "wsu" + (cnt == 0 ? "" : cnt);
                if (!StringUtils.isEmpty(elem.lookupNamespaceURI(pfx))) {
                    pfx = null;
                    cnt++;
                }
            }
            if (!found) {
                idAttr = elem.getOwnerDocument().createAttributeNS(WSDLConstants.NS_XMLNS, "xmlns:" + pfx);
                idAttr.setValue(PolicyConstants.WSU_NAMESPACE_URI);
                elem.setAttributeNodeNS(idAttr);
            }
            idAttr = elem.getOwnerDocument().createAttributeNS(PolicyConstants.WSU_NAMESPACE_URI, 
                                                               pfx + ":Id");
            idAttr.setValue(id);
            elem.setAttributeNodeNS(idAttr);
        }
        
        return id;
    }

    public List<WSEncryptionPart> getEncryptedParts() 
        throws SOAPException {
        
        boolean isBody = false;
        
        SignedEncryptedParts parts = null;
        SignedEncryptedElements elements = null;
        ContentEncryptedElements celements = null;

        Collection<AssertionInfo> ais = aim.getAssertionInfo(SP12Constants.ENCRYPTED_PARTS);
        if (ais != null) {
            for (AssertionInfo ai : ais) {
                parts = (SignedEncryptedParts)ai.getAssertion();
                ai.setAsserted(true);
            }            
        }
        ais = aim.getAssertionInfo(SP12Constants.ENCRYPTED_ELEMENTS);
        if (ais != null) {
            for (AssertionInfo ai : ais) {
                elements = (SignedEncryptedElements)ai.getAssertion();
                ai.setAsserted(true);
            }            
        }
        ais = aim.getAssertionInfo(SP12Constants.CONTENT_ENCRYPTED_ELEMENTS);
        if (ais != null) {
            for (AssertionInfo ai : ais) {
                celements = (ContentEncryptedElements)ai.getAssertion();
                ai.setAsserted(true);
            }            
        }
        
        List<WSEncryptionPart> signedParts = new ArrayList<WSEncryptionPart>();
        if (parts != null) {
            isBody = parts.isBody();
            for (Header head : parts.getHeaders()) {
                WSEncryptionPart wep = new WSEncryptionPart(head.getName(),
                                                            head.getNamespace(),
                                                            "Element");
                signedParts.add(wep);
            }
        }
    
        // REVISIT consider catching exceptions and unassert failed assertions or
        // to process and assert them one at a time.  Additionally, a found list
        // should be applied to all operations that involve adding anything to
        // the encrypted list to prevent duplication / errors in encryption.
        return getPartsAndElements(false, 
                                   isBody,
                                   signedParts,
                                   elements == null ? null : elements.getXPathExpressions(),
                                   elements == null ? null : elements.getDeclaredNamespaces(),
                                   celements == null ? null : celements.getXPathExpressions(),
                                   celements == null ? null : celements.getDeclaredNamespaces());
    }    
    
    public List<WSEncryptionPart> getSignedParts() 
        throws SOAPException {
        
        boolean isSignBody = false;
        
        SignedEncryptedParts parts = null;
        SignedEncryptedElements elements = null;
        
        Collection<AssertionInfo> ais = aim.getAssertionInfo(SP12Constants.SIGNED_PARTS);
        if (ais != null) {
            for (AssertionInfo ai : ais) {
                parts = (SignedEncryptedParts)ai.getAssertion();
                ai.setAsserted(true);
            }            
        }
        ais = aim.getAssertionInfo(SP12Constants.SIGNED_ELEMENTS);
        if (ais != null) {
            for (AssertionInfo ai : ais) {
                elements = (SignedEncryptedElements)ai.getAssertion();
                ai.setAsserted(true);
            }            
        }
        
        List<WSEncryptionPart> signedParts = new ArrayList<WSEncryptionPart>();
        if (parts != null) {
            isSignBody = parts.isBody();
            for (Header head : parts.getHeaders()) {
                WSEncryptionPart wep = new WSEncryptionPart(head.getName(),
                                                            head.getNamespace(),
                                                            "Element");
                signedParts.add(wep);
            }
        }
        
        // REVISIT consider catching exceptions and unassert failed assertions or
        // to process and assert them one at a time.  Additionally, a found list
        // should be applied to all operations that involve adding anything to
        // the signed list to prevent duplication in the signature.
        return getPartsAndElements(true, 
                                   isSignBody,
                                   signedParts,
                                   elements == null ? null : elements.getXPathExpressions(),
                                   elements == null ? null : elements.getDeclaredNamespaces(),
                                   null, null);
    }

    /**
     * Identifies the portions of the message to be signed/encrypted.
     * 
     * @param sign
     *            whether the matches are to be signed or encrypted
     * @param includeBody
     *            if the body should be included in the signature/encryption
     * @param parts
     *            any {@code WSEncryptionPart}s to match for signature or
     *            encryption as specified by WS-SP signed parts or encrypted
     *            parts. Parts without a name match all elements with the
     *            provided namespace.
     * @param xpaths
     *            any XPath expressions to sign/encrypt matches
     * @param namespaces
     *            namespace prefix to namespace mappings for XPath expressions
     *            in {@code xpaths}
     * @param contentXpaths
     *            any XPath expressions to content encrypt
     * @param cnamespaces
     *            namespace prefix to namespace mappings for XPath expressions
     *            in {@code contentXpaths}
     * @return a configured list of {@code WSEncryptionPart}s suitable for
     *         processing by WSS4J
     * @throws SOAPException
     *             if there is an error extracting SOAP content from the SAAJ
     *             model
     *             
     * @deprecated Use {@link #getSignedParts()} and {@link #getEncryptedParts()}
     *             instead.
     */
    public List<WSEncryptionPart> getPartsAndElements(boolean sign, 
                                                    boolean includeBody,
                                                    List<WSEncryptionPart> parts,
                                                    List<String> xpaths, 
                                                    Map<String, String> namespaces,
                                                    List<String> contentXpaths,
                                                    Map<String, String> cnamespaces) 
        throws SOAPException {
        
        List<WSEncryptionPart> result = new ArrayList<WSEncryptionPart>();
        
        List<Element> found = new ArrayList<Element>();
        
        // Handle sign/enc parts
        result.addAll(this.getParts(sign, includeBody, parts, found));
        
        
        // Handle sign/enc elements
        try {
            result.addAll(this.getElements("Element", xpaths, namespaces, found));
        } catch (XPathExpressionException e) {  
            // REVISIT
        }
        
        // Handle content encrypted elements
        try {
            result.addAll(this.getElements("Content", contentXpaths, cnamespaces, found));
        } catch (XPathExpressionException e) {
            // REVISIT
        }
        
        return result;
    }
    
    /**
     * Identifies the portions of the message to be signed/encrypted.
     * 
     * @param sign
     *            whether the matches are to be signed or encrypted
     * @param includeBody
     *            if the body should be included in the signature/encryption
     * @param parts
     *            any {@code WSEncryptionPart}s to match for signature or
     *            encryption as specified by WS-SP signed parts or encrypted
     *            parts. Parts without a name match all elements with the
     *            provided namespace.
     * @param found 
     *            a list of elements that have previously been tagged for
     *            signing/encryption. Populated with additional matches found by
     *            this method and used to prevent including the same element
     *            twice under the same operation.
     * @return a configured list of {@code WSEncryptionPart}s suitable for
     *         processing by WSS4J
     * @throws SOAPException
     *             if there is an error extracting SOAP content from the SAAJ
     *             model
     */
    private List<WSEncryptionPart> getParts(boolean sign,
            boolean includeBody, List<WSEncryptionPart> parts,
            List<Element> found) throws SOAPException {
        
        List<WSEncryptionPart> result = new ArrayList<WSEncryptionPart>();
        
        if (includeBody && !found.contains(this.saaj.getSOAPBody())) {
            found.add(saaj.getSOAPBody());
            final String id = this.addWsuIdToElement(this.saaj.getSOAPBody());
            if (sign) {
                WSEncryptionPart bodyPart = new WSEncryptionPart(id, "Element");
                bodyPart.setElement(this.saaj.getSOAPBody());
                result.add(bodyPart);
            } else {
                WSEncryptionPart bodyPart = new WSEncryptionPart(id, "Content");
                bodyPart.setElement(this.saaj.getSOAPBody());
                result.add(bodyPart);
            }
        }
        
        final SOAPHeader header = saaj.getSOAPHeader();
        
        // Handle sign/enc parts
        for (WSEncryptionPart part : parts) {
            final List<Element> elements;
            
            if (StringUtils.isEmpty(part.getName())) {
                // An entire namespace
                elements = 
                    DOMUtils.getChildrenWithNamespace(header, part.getNamespace());    
            } else {
                // All elements with a given name and namespace 
                elements = 
                    DOMUtils.getChildrenWithName(header, part.getNamespace(), part.getName());
            }
            
            for (Element el : elements) {
                if (!found.contains(el)) {
                    found.add(el);
                    // Generate an ID for the element and use this ID or else
                    // WSS4J will only ever sign/encrypt the first matching
                    // element with the same name and namespace as that in the
                    // WSEncryptionPart
                    final String id = this.addWsuIdToElement(el);
                    WSEncryptionPart elPart = 
                        new WSEncryptionPart(id, part.getEncModifier());
                    elPart.setElement(el);
                    result.add(elPart);
                }
            }
        }
        
        return result;
    }
    
    /**
     * Identifies the portions of the message to be signed/encrypted.
     * 
     * @param encryptionModifier
     *            indicates the scope of the crypto operation over matched
     *            elements. Either "Content" or "Element".
     * @param xpaths
     *            any XPath expressions to sign/encrypt matches
     * @param namespaces
     *            namespace prefix to namespace mappings for XPath expressions
     *            in {@code xpaths}
     * @param found
     *            a list of elements that have previously been tagged for
     *            signing/encryption. Populated with additional matches found by
     *            this method and used to prevent including the same element
     *            twice under the same operation.
     * @return a configured list of {@code WSEncryptionPart}s suitable for
     *         processing by WSS4J
     * @throws XPathExpressionException
     *             if a provided XPath is invalid
     * @throws SOAPException
     *             if there is an error extracting SOAP content from the SAAJ
     *             model
     */
    private List<WSEncryptionPart> getElements(String encryptionModifier,
            List<String> xpaths, Map<String, String> namespaces,
            List<Element> found) throws XPathExpressionException, SOAPException {
        
        List<WSEncryptionPart> result = new ArrayList<WSEncryptionPart>();
        
        if (xpaths != null && !xpaths.isEmpty()) {
            XPathFactory factory = XPathFactory.newInstance();
            for (String expression : xpaths) {
                XPath xpath = factory.newXPath();
                if (namespaces != null) {
                    xpath.setNamespaceContext(new MapNamespaceContext(namespaces));
                }
               
                NodeList list = (NodeList)xpath.evaluate(expression, saaj.getSOAPPart().getEnvelope(),
                                               XPathConstants.NODESET);
                for (int x = 0; x < list.getLength(); x++) {
                    Element el = (Element)list.item(x);
                    
                    if (!found.contains(el)) {
                        final String id = this.addWsuIdToElement(el);
                        
                        WSEncryptionPart part = 
                            new WSEncryptionPart(id, encryptionModifier);
                        part.setElement(el);
                        part.setXpath(expression);
                        
                        result.add(part);
                    }
                }
            }
        }
        
        return result;
    }
    
    protected WSSecEncryptedKey getEncryptedKeyBuilder(TokenWrapper wrapper, 
                                                       Token token) throws WSSecurityException {
        WSSecEncryptedKey encrKey = new WSSecEncryptedKey(wssConfig);
        Crypto crypto = getEncryptionCrypto(wrapper);
        message.getExchange().put(SecurityConstants.ENCRYPT_CRYPTO, crypto);
        setKeyIdentifierType(encrKey, wrapper, token);
        setEncryptionUser(encrKey, wrapper, false, crypto);
        encrKey.setKeySize(binding.getAlgorithmSuite().getMaximumSymmetricKeyLength());
        encrKey.setKeyEncAlgo(binding.getAlgorithmSuite().getAsymmetricKeyWrap());
        
        encrKey.prepare(saaj.getSOAPPart(), crypto);
        
        return encrKey;
    }

    public Crypto getSignatureCrypto(TokenWrapper wrapper) throws WSSecurityException {
        return getCrypto(wrapper, SecurityConstants.SIGNATURE_CRYPTO,
                         SecurityConstants.SIGNATURE_PROPERTIES);
    }


    public Crypto getEncryptionCrypto(TokenWrapper wrapper) throws WSSecurityException {
        return getCrypto(wrapper, 
                         SecurityConstants.ENCRYPT_CRYPTO,
                         SecurityConstants.ENCRYPT_PROPERTIES);
    }
    
    public Crypto getCrypto(
        TokenWrapper wrapper, 
        String cryptoKey, 
        String propKey
    ) throws WSSecurityException {
        Crypto crypto = (Crypto)message.getContextualProperty(cryptoKey);
        if (crypto != null) {
            return crypto;
        }
        
        Object o = message.getContextualProperty(propKey);
        if (o == null) {
            return null;
        }
        
        crypto = getCryptoCache().get(o);
        if (crypto != null) {
            return crypto;
        }
        Properties properties = null;
        if (o instanceof Properties) {
            properties = (Properties)o;
        } else if (o instanceof String) {
            ResourceManager rm = message.getExchange().get(Bus.class).getExtension(ResourceManager.class);
            URL url = rm.resolveResource((String)o, URL.class);
            try {
                if (url == null) {
                    url = ClassLoaderUtils.getResource((String)o, this.getClass());
                }
                if (url == null) {
                    try {
                        url = new URL((String)o);
                    } catch (Exception ex) {
                        //ignore
                    }
                }
                if (url != null) {
                    InputStream ins = url.openStream();
                    properties = new Properties();
                    properties.load(ins);
                    ins.close();
                } else {
                    policyNotAsserted(wrapper, "Could not find properties file " + o);
                }
            } catch (IOException e) {
                policyNotAsserted(wrapper, e);
            }
        } else if (o instanceof URL) {
            properties = new Properties();
            try {
                InputStream ins = ((URL)o).openStream();
                properties.load(ins);
                ins.close();
            } catch (IOException e) {
                policyNotAsserted(wrapper, e);
            }            
        }
        
        if (properties != null) {
            crypto = CryptoFactory.getInstance(properties);
            getCryptoCache().put(o, crypto);
        }
        return crypto;
    }
    
    public void setKeyIdentifierType(WSSecBase secBase, TokenWrapper wrapper, Token token) {
        
        if (token.getInclusion() == SPConstants.IncludeTokenType.INCLUDE_TOKEN_NEVER) {
            boolean tokenTypeSet = false;
            
            if (token instanceof X509Token) {
                X509Token x509Token = (X509Token)token;
                if (x509Token.isRequireIssuerSerialReference()) {
                    secBase.setKeyIdentifierType(WSConstants.ISSUER_SERIAL);
                    tokenTypeSet = true;
                } else if (x509Token.isRequireKeyIdentifierReference()) {
                    secBase.setKeyIdentifierType(WSConstants.SKI_KEY_IDENTIFIER);
                    tokenTypeSet = true;
                } else if (x509Token.isRequireThumbprintReference()) {
                    secBase.setKeyIdentifierType(WSConstants.THUMBPRINT_IDENTIFIER);
                    tokenTypeSet = true;
                }
            } else if (token instanceof KeyValueToken) {
                secBase.setKeyIdentifierType(WSConstants.KEY_VALUE);
                tokenTypeSet = true;
            }
            
            if (!tokenTypeSet) {
                policyAsserted(token);
                policyAsserted(wrapper);
                
                Wss10 wss = getWss10();
                policyAsserted(wss);
                if (wss.isMustSupportRefKeyIdentifier()) {
                    secBase.setKeyIdentifierType(WSConstants.SKI_KEY_IDENTIFIER);
                } else if (wss.isMustSupportRefIssuerSerial()) {
                    secBase.setKeyIdentifierType(WSConstants.ISSUER_SERIAL);
                } else if (wss instanceof Wss11
                                && ((Wss11) wss).isMustSupportRefThumbprint()) {
                    secBase.setKeyIdentifierType(WSConstants.THUMBPRINT_IDENTIFIER);
                }
            }
        } else {
            policyAsserted(token);
            policyAsserted(wrapper);
            secBase.setKeyIdentifierType(WSConstants.BST_DIRECT_REFERENCE);
        }
    }
    
    public void setEncryptionUser(WSSecEncryptedKey encrKeyBuilder, TokenWrapper token,
                                  boolean sign, Crypto crypto) {
        String encrUser = (String)message.getContextualProperty(sign 
                                                                ? SecurityConstants.SIGNATURE_USERNAME
                                                                : SecurityConstants.ENCRYPT_USERNAME);
        if (crypto != null && encrUser == null) {
            try {
                encrUser = crypto.getDefaultX509Identifier();
            } catch (WSSecurityException e1) {
                throw new Fault(e1);
            }
        } else if (encrUser == null || "".equals(encrUser)) {
            policyNotAsserted(token, "No " + (sign ? "signature" : "encryption") + " crypto object found.");
        }
        if (encrUser == null || "".equals(encrUser)) {
            policyNotAsserted(token, "No " + (sign ? "signature" : "encryption") + " username found.");
        }
        if (WSHandlerConstants.USE_REQ_SIG_CERT.equals(encrUser)) {
            List<WSHandlerResult> results = 
                CastUtils.cast((List<?>)
                    message.getExchange().getInMessage().get(WSHandlerConstants.RECV_RESULTS));
            if (results != null) {
                encrKeyBuilder.setUseThisCert(getReqSigCert(results));
                 
                //TODO This is a hack, this should not come under USE_REQ_SIG_CERT
                if (encrKeyBuilder.isCertSet()) {
                    encrKeyBuilder.setUserInfo(getUsername(results));
                }
            } else {
                policyNotAsserted(token, "No security results in incoming message");
            }
        } else {
            encrKeyBuilder.setUserInfo(encrUser);
        }
    }
    
    private static X509Certificate getReqSigCert(List<WSHandlerResult> results) {
        /*
        * Scan the results for a matching actor. Use results only if the
        * receiving Actor and the sending Actor match.
        */
        for (WSHandlerResult rResult : results) {
            List<WSSecurityEngineResult> wsSecEngineResults = rResult.getResults();
            /*
            * Scan the results for the first Signature action. Use the
            * certificate of this Signature to set the certificate for the
            * encryption action :-).
            */
            for (WSSecurityEngineResult wser : wsSecEngineResults) {
                Integer actInt = (Integer)wser.get(WSSecurityEngineResult.TAG_ACTION);
                if (actInt.intValue() == WSConstants.SIGN) {
                    return (X509Certificate)wser.get(WSSecurityEngineResult.TAG_X509_CERTIFICATE);
                }
            }
        }
        
        return null;
    }
    
    /**
     * Scan through <code>WSHandlerResult<code> list for a Username token and return
     * the username if a Username Token found 
     * @param results
     * @return
     */
    public static String getUsername(List<WSHandlerResult> results) {
        /*
         * Scan the results for a matching actor. Use results only if the
         * receiving Actor and the sending Actor match.
         */
        for (WSHandlerResult rResult : results) {
            List<WSSecurityEngineResult> wsSecEngineResults = rResult.getResults();
            /*
             * Scan the results for a username token. Use the username
             * of this token to set the alias for the encryption user
             */
            for (WSSecurityEngineResult wser : wsSecEngineResults) {
                Integer actInt = (Integer)wser.get(WSSecurityEngineResult.TAG_ACTION);
                if (actInt.intValue() == WSConstants.UT) {
                    WSUsernameTokenPrincipal principal 
                        = (WSUsernameTokenPrincipal)wser.get(WSSecurityEngineResult.TAG_PRINCIPAL);
                    return principal.getName();
                }
            }
        }
         
        return null;
    }
    
    protected Wss10 getWss10() {
        Collection<AssertionInfo> ais = aim.getAssertionInfo(SP12Constants.WSS10);
        if (ais != null) {
            for (AssertionInfo ai : ais) {
                return (Wss10)ai.getAssertion();
            }            
        }        
        ais = aim.getAssertionInfo(SP12Constants.WSS11);
        if (ais != null) {
            for (AssertionInfo ai : ais) {
                return (Wss10)ai.getAssertion();
            }            
        }   
        return null;
    }

    private void checkForX509PkiPath(WSSecSignature sig, Token token) {
        if (token instanceof X509Token) {
            X509Token x509Token = (X509Token) token;
            if (x509Token.getTokenVersionAndType().equals(SPConstants.WSS_X509_PKI_PATH_V1_TOKEN10)
                    || x509Token.getTokenVersionAndType().equals(SPConstants.WSS_X509_PKI_PATH_V1_TOKEN11)) {
                sig.setUseSingleCertificate(false);
            }
        }
    }
    
    protected WSSecSignature getSignatureBuilder(
        TokenWrapper wrapper, Token token, boolean endorse
    ) throws WSSecurityException {
        return getSignatureBuilder(wrapper, token, false, endorse);
    }
    
    protected WSSecSignature getSignatureBuilder(
        TokenWrapper wrapper, Token token, boolean attached, boolean endorse
    ) throws WSSecurityException {
        WSSecSignature sig = new WSSecSignature(wssConfig);
        checkForX509PkiPath(sig, token);
        if (token instanceof IssuedToken) {
            policyAsserted(token);
            policyAsserted(wrapper);
            SecurityToken securityToken = getSecurityToken();
            String tokenType = securityToken.getTokenType();
            
            int type = attached ? WSConstants.CUSTOM_SYMM_SIGNING 
                : WSConstants.CUSTOM_SYMM_SIGNING_DIRECT;
            if (WSConstants.WSS_SAML_TOKEN_TYPE.equals(tokenType)) {
                sig.setCustomTokenValueType(WSConstants.WSS_SAML_KI_VALUE_TYPE);
                sig.setKeyIdentifierType(WSConstants.CUSTOM_KEY_IDENTIFIER);
            } else if (WSConstants.WSS_SAML2_TOKEN_TYPE.equals(tokenType)) {
                sig.setCustomTokenValueType(WSConstants.WSS_SAML2_KI_VALUE_TYPE);
                sig.setKeyIdentifierType(WSConstants.CUSTOM_KEY_IDENTIFIER);
            } else {
                sig.setCustomTokenValueType(tokenType);
                sig.setKeyIdentifierType(type);
            }
            
            String sigTokId;
            if (attached) {
                sigTokId = securityToken.getWsuId();
                if (sigTokId == null) {
                    sigTokId = securityToken.getId();                    
                }
                if (sigTokId.startsWith("#")) {
                    sigTokId = sigTokId.substring(1);
                }
            } else {
                sigTokId = securityToken.getId();
            }
            
            sig.setCustomTokenId(sigTokId);
        } else {
            setKeyIdentifierType(sig, wrapper, token);
        }
        
        boolean encryptCrypto = false;
        String userNameKey = SecurityConstants.SIGNATURE_USERNAME;
        String type = "signature";
        if (binding instanceof SymmetricBinding && !endorse) {
            encryptCrypto = ((SymmetricBinding)binding).getProtectionToken() != null;
            userNameKey = SecurityConstants.ENCRYPT_USERNAME;
        }

        Crypto crypto = encryptCrypto ? getEncryptionCrypto(wrapper) 
            : getSignatureCrypto(wrapper);
        
        if (endorse && crypto == null && binding instanceof SymmetricBinding) {
            userNameKey = SecurityConstants.ENCRYPT_USERNAME;
            crypto = getEncryptionCrypto(wrapper);
        }
        
        if (!endorse) {
            message.getExchange().put(SecurityConstants.SIGNATURE_CRYPTO, crypto);
        }
        String user = (String)message.getContextualProperty(userNameKey);
        if (crypto != null && StringUtils.isEmpty(user)) {
            try {
                user = crypto.getDefaultX509Identifier();
            } catch (WSSecurityException e1) {
                throw new Fault(e1);
            }
        }
        if (StringUtils.isEmpty(user)) {
            policyNotAsserted(token, "No " + type + " username found.");
            return null;
        }

        String password = getPassword(user, token, WSPasswordCallback.SIGNATURE);
        if (password == null) {
            password = "";
        }
        sig.setUserInfo(user, password);
        sig.setSignatureAlgorithm(binding.getAlgorithmSuite().getAsymmetricSignature());
        sig.setDigestAlgo(binding.getAlgorithmSuite().getDigest());
        sig.setSigCanonicalization(binding.getAlgorithmSuite().getInclusiveC14n());
        sig.setWsConfig(wssConfig);
        try {
            sig.prepare(saaj.getSOAPPart(), crypto, secHeader);
        } catch (WSSecurityException e) {
            policyNotAsserted(token, e);
        }
        
        return sig;
    }

    protected void doEndorsedSignatures(Map<Token, Object> tokenMap,
                                        boolean isTokenProtection,
                                        boolean isSigProtect) {
        
        for (Map.Entry<Token, Object> ent : tokenMap.entrySet()) {
            WSSecBase tempTok = (WSSecBase)ent.getValue();
            
            List<WSEncryptionPart> sigParts = new ArrayList<WSEncryptionPart>();
            WSEncryptionPart sigPart = new WSEncryptionPart(mainSigId);
            sigPart.setElement(bottomUpElement);
            sigParts.add(sigPart);
            
            if (tempTok instanceof WSSecSignature) {
                WSSecSignature sig = (WSSecSignature)tempTok;
                if (isTokenProtection && sig.getBSTTokenId() != null) {
                    WSEncryptionPart bstPart = 
                        new WSEncryptionPart(sig.getBSTTokenId());
                    bstPart.setElement(sig.getBinarySecurityTokenElement());
                    sigParts.add(bstPart);
                }
                try {
                    List<Reference> referenceList = sig.addReferencesToSign(sigParts, secHeader);
                    sig.computeSignature(referenceList, false, null);
                    
                    signatures.add(sig.getSignatureValue());
                    if (isSigProtect) {
                        encryptedTokensIdList.add(sig.getId());
                    }
                } catch (WSSecurityException e) {
                    policyNotAsserted(ent.getKey(), e);
                }
                
            } else if (tempTok instanceof WSSecurityTokenHolder) {
                SecurityToken token = ((WSSecurityTokenHolder)tempTok).getToken();
                if (isTokenProtection) {
                    sigParts.add(new WSEncryptionPart(token.getId()));
                }
                
                try {
                    if (ent.getKey().isDerivedKeys()) {
                        doSymmSignatureDerived(ent.getKey(), token, sigParts, isTokenProtection);
                    } else {
                        doSymmSignature(ent.getKey(), token, sigParts, isTokenProtection);
                    }
                } catch (Exception e) {
                    // TODO Auto-generated catch block
                    e.printStackTrace();
                }
            }
        } 
    }
    
    private void doSymmSignatureDerived(Token policyToken, SecurityToken tok,
                                 List<WSEncryptionPart> sigParts, boolean isTokenProtection)
        throws WSSecurityException, ConversationException {
        
        Document doc = saaj.getSOAPPart();
        WSSecDKSign dkSign = new WSSecDKSign(wssConfig);  
        
        //Check whether it is security policy 1.2 and use the secure conversation accordingly
        if (SP12Constants.INSTANCE == policyToken.getSPConstants()) {
            dkSign.setWscVersion(ConversationConstants.VERSION_05_12);
        }
                      
        //Check for whether the token is attached in the message or not
        boolean attached = false;
        
        if (SPConstants.IncludeTokenType.INCLUDE_TOKEN_ALWAYS == policyToken.getInclusion()
            || SPConstants.IncludeTokenType.INCLUDE_TOKEN_ONCE == policyToken.getInclusion()
            || (isRequestor() && SPConstants.IncludeTokenType.INCLUDE_TOKEN_ALWAYS_TO_RECIPIENT 
                    == policyToken.getInclusion())) {
            attached = true;
        }
        
        // Setting the AttachedReference or the UnattachedReference according to the flag
        Element ref;
        if (attached) {
            ref = tok.getAttachedReference();
        } else {
            ref = tok.getUnattachedReference();
        }
        
        if (ref != null) {
            ref = cloneElement(ref);
            dkSign.setExternalKey(tok.getSecret(), ref);
        } else if (!isRequestor() && policyToken.isDerivedKeys()) { 
            // If the Encrypted key used to create the derived key is not
            // attached use key identifier as defined in WSS1.1 section
            // 7.7 Encrypted Key reference
            SecurityTokenReference tokenRef 
                = new SecurityTokenReference(doc);
            if (tok.getSHA1() != null) {
                tokenRef.setKeyIdentifierEncKeySHA1(tok.getSHA1());
                tokenRef.addTokenType(WSConstants.WSS_ENC_KEY_VALUE_TYPE);
            }
            dkSign.setExternalKey(tok.getSecret(), tokenRef.getElement());
        
        } else {
            dkSign.setExternalKey(tok.getSecret(), tok.getId());
        }

        //Set the algo info
        dkSign.setSignatureAlgorithm(binding.getAlgorithmSuite().getSymmetricSignature());
        dkSign.setDerivedKeyLength(binding.getAlgorithmSuite().getSignatureDerivedKeyLength() / 8);
        if (tok.getSHA1() != null) {
            //Set the value type of the reference
            dkSign.setCustomValueType(WSConstants.SOAPMESSAGE_NS11 + "#"
                + WSConstants.ENC_KEY_VALUE_TYPE);
        }
        
        dkSign.prepare(doc, secHeader);
        
        if (isTokenProtection) {
            //Hack to handle reference id issues
            //TODO Need a better fix
            String sigTokId = tok.getId();
            if (sigTokId.startsWith("#")) {
                sigTokId = sigTokId.substring(1);
            }
            sigParts.add(new WSEncryptionPart(sigTokId));
        }
        
        dkSign.setParts(sigParts);
        
        List<Reference> referenceList = dkSign.addReferencesToSign(sigParts, secHeader);
        
        //Add elements to header
        addSupportingElement(dkSign.getdktElement());
        
        //Do signature
        dkSign.computeSignature(referenceList, false, null);
        
        signatures.add(dkSign.getSignatureValue());
    }
    
    private void doSymmSignature(Token policyToken, SecurityToken tok,
                                         List<WSEncryptionPart> sigParts, boolean isTokenProtection)
        throws WSSecurityException, ConversationException {
        
        Document doc = saaj.getSOAPPart();
        WSSecSignature sig = new WSSecSignature(wssConfig);
        // If a EncryptedKeyToken is used, set the correct value type to
        // be used in the wsse:Reference in ds:KeyInfo
        if (policyToken instanceof X509Token) {
            if (isRequestor()) {
                // TODO Add support for SAML2 here
                sig.setCustomTokenValueType(
                    WSConstants.SOAPMESSAGE_NS11 + "#" + WSConstants.ENC_KEY_VALUE_TYPE
                );
                sig.setKeyIdentifierType(WSConstants.CUSTOM_SYMM_SIGNING);
            } else {
                //the tok has to be an EncryptedKey token
                sig.setEncrKeySha1value(tok.getSHA1());
                sig.setKeyIdentifierType(WSConstants.ENCRYPTED_KEY_SHA1_IDENTIFIER);
            }
            
        } else {
            if (tok.getTokenType() != null) {
                sig.setCustomTokenValueType(tok.getTokenType());
            } else {
                // TODO Add support for SAML2 here
                sig.setCustomTokenValueType(WSConstants.WSS_SAML_KI_VALUE_TYPE);
            }
            sig.setKeyIdentifierType(WSConstants.CUSTOM_SYMM_SIGNING);
        }
        
        String sigTokId = tok.getWsuId();
        if (sigTokId == null) {
            sigTokId = tok.getId();
        }
                       
        //Hack to handle reference id issues
        //TODO Need a better fix
        if (sigTokId.startsWith("#")) {
            sigTokId = sigTokId.substring(1);
        }
        
        sig.setCustomTokenId(sigTokId);
        sig.setSecretKey(tok.getSecret());
        sig.setSignatureAlgorithm(binding.getAlgorithmSuite().getAsymmetricSignature());
        sig.setSignatureAlgorithm(binding.getAlgorithmSuite().getSymmetricSignature());
        sig.prepare(doc, getSignatureCrypto(null), secHeader);

        sig.setParts(sigParts);
        List<Reference> referenceList = sig.addReferencesToSign(sigParts, secHeader);

        //Do signature
        sig.computeSignature(referenceList, false, null);
        signatures.add(sig.getSignatureValue());
    }
    
    protected void assertSupportingTokens(List<WSEncryptionPart> sigs) {
        assertSupportingTokens(findAndAssertPolicy(SP12Constants.SIGNED_SUPPORTING_TOKENS));
        assertSupportingTokens(findAndAssertPolicy(SP12Constants.ENDORSING_SUPPORTING_TOKENS));
        assertSupportingTokens(findAndAssertPolicy(SP12Constants.SIGNED_ENDORSING_SUPPORTING_TOKENS));
        assertSupportingTokens(findAndAssertPolicy(SP12Constants.SIGNED_ENCRYPTED_SUPPORTING_TOKENS));
        assertSupportingTokens(findAndAssertPolicy(SP12Constants.ENDORSING_ENCRYPTED_SUPPORTING_TOKENS));
        assertSupportingTokens(findAndAssertPolicy(SP12Constants
                                                       .SIGNED_ENDORSING_ENCRYPTED_SUPPORTING_TOKENS));
        assertSupportingTokens(findAndAssertPolicy(SP12Constants.SUPPORTING_TOKENS));
        assertSupportingTokens(findAndAssertPolicy(SP12Constants.ENCRYPTED_SUPPORTING_TOKENS));
    }
    
    protected void addSupportingTokens(List<WSEncryptionPart> sigs) throws WSSecurityException {
        
        Collection<Assertion> sgndSuppTokens = 
            findAndAssertPolicy(SP12Constants.SIGNED_SUPPORTING_TOKENS);
        
        Map<Token, Object> sigSuppTokMap = this.handleSupportingTokens(sgndSuppTokens, false);           
        
        Collection<Assertion> endSuppTokens = 
            findAndAssertPolicy(SP12Constants.ENDORSING_SUPPORTING_TOKENS);

        endSuppTokMap = this.handleSupportingTokens(endSuppTokens, true);

        Collection<Assertion> sgndEndSuppTokens 
            = findAndAssertPolicy(SP12Constants.SIGNED_ENDORSING_SUPPORTING_TOKENS);
        sgndEndSuppTokMap = this.handleSupportingTokens(sgndEndSuppTokens, true);
        
        Collection<Assertion> sgndEncryptedSuppTokens 
            = findAndAssertPolicy(SP12Constants.SIGNED_ENCRYPTED_SUPPORTING_TOKENS);
        Map<Token, Object> sgndEncSuppTokMap 
            = this.handleSupportingTokens(sgndEncryptedSuppTokens, false);
        
        Collection<Assertion> endorsingEncryptedSuppTokens 
            = findAndAssertPolicy(SP12Constants.ENDORSING_ENCRYPTED_SUPPORTING_TOKENS);
        endEncSuppTokMap 
            = this.handleSupportingTokens(endorsingEncryptedSuppTokens, true);

        Collection<Assertion> sgndEndEncSuppTokens 
            = findAndAssertPolicy(SP12Constants.SIGNED_ENDORSING_ENCRYPTED_SUPPORTING_TOKENS);
        sgndEndEncSuppTokMap 
            = this.handleSupportingTokens(sgndEndEncSuppTokens, true);

        Collection<Assertion> supportingToks 
            = findAndAssertPolicy(SP12Constants.SUPPORTING_TOKENS);
        this.handleSupportingTokens(supportingToks, false);

        Collection<Assertion> encryptedSupportingToks 
            = findAndAssertPolicy(SP12Constants.ENCRYPTED_SUPPORTING_TOKENS);
        this.handleSupportingTokens(encryptedSupportingToks, false);

        //Setup signature parts
        addSignatureParts(sigSuppTokMap, sigs);
        addSignatureParts(sgndEncSuppTokMap, sigs);
        addSignatureParts(sgndEndSuppTokMap, sigs);
        addSignatureParts(sgndEndEncSuppTokMap, sigs);
    }
    
    protected void doEndorse() {
        boolean tokenProtect = false;
        boolean sigProtect = false;
        if (binding instanceof AsymmetricBinding) {
            tokenProtect = ((AsymmetricBinding)binding).isTokenProtection();
            sigProtect = ((AsymmetricBinding)binding).isSignatureProtection();            
        } else if (binding instanceof SymmetricBinding) {
            tokenProtect = ((SymmetricBinding)binding).isTokenProtection();
            sigProtect = ((SymmetricBinding)binding).isSignatureProtection();            
        }
        // Adding the endorsing encrypted supporting tokens to endorsing supporting tokens
        endSuppTokMap.putAll(endEncSuppTokMap);
        // Do endorsed signatures
        doEndorsedSignatures(endSuppTokMap, tokenProtect, sigProtect);

        //Adding the signed endorsed encrypted tokens to signed endorsed supporting tokens
        sgndEndSuppTokMap.putAll(sgndEndEncSuppTokMap);
        // Do signed endorsing signatures
        doEndorsedSignatures(sgndEndSuppTokMap, tokenProtect, sigProtect);
    } 

    protected void addSignatureConfirmation(List<WSEncryptionPart> sigParts) {
        Wss10 wss10 = getWss10();
        
        if (!(wss10 instanceof Wss11) 
            || !((Wss11)wss10).isRequireSignatureConfirmation()) {
            //If we don't require sig confirmation simply go back :-)
            return;
        }
        
        List<WSHandlerResult> results = 
            CastUtils.cast((List<?>)
                message.getExchange().getInMessage().get(WSHandlerConstants.RECV_RESULTS));
        /*
         * loop over all results gathered by all handlers in the chain. For each
         * handler result get the various actions. After that loop we have all
         * signature results in the signatureActions list
         */
        List<WSSecurityEngineResult> signatureActions = new ArrayList<WSSecurityEngineResult>();
        for (WSHandlerResult wshResult : results) {
            WSSecurityUtil.fetchAllActionResults(wshResult.getResults(),
                    WSConstants.SIGN, signatureActions);
            WSSecurityUtil.fetchAllActionResults(wshResult.getResults(),
                    WSConstants.ST_SIGNED, signatureActions);
            WSSecurityUtil.fetchAllActionResults(wshResult.getResults(),
                    WSConstants.UT_SIGN, signatureActions);
        }
        
        // prepare a SignatureConfirmation token
        WSSecSignatureConfirmation wsc = new WSSecSignatureConfirmation(wssConfig);
        if (signatureActions.size() > 0) {
            for (WSSecurityEngineResult wsr : signatureActions) {
                byte[] sigVal = (byte[]) wsr.get(WSSecurityEngineResult.TAG_SIGNATURE_VALUE);
                wsc.setSignatureValue(sigVal);
                wsc.prepare(saaj.getSOAPPart());
                addSupportingElement(wsc.getSignatureConfirmationElement());
                if (sigParts != null) {
                    sigParts.add(new WSEncryptionPart(wsc.getId()));
                }
            }
        } else {
            //No Sig value
            wsc.prepare(saaj.getSOAPPart());
            addSupportingElement(wsc.getSignatureConfirmationElement());
            if (sigParts != null) {
                sigParts.add(new WSEncryptionPart(wsc.getId()));
            }
        }
    }
    
    /**
     * Processes the parts to be signed and reconfigures those parts that have
     * already been encrypted.
     * 
     * @param encryptedParts
     *            the parts that have been encrypted
     * @param signedParts
     *            the parts that are to be signed
     * 
     * @throws IllegalArgumentException
     *             if an element in {@code signedParts} contains a {@code
     *             WSEncryptionPart} with a {@code null} {@code id} value
     *             and the {@code WSEncryptionPart} {@code name} value is not
     *             "Token"
     */
    public void handleEncryptedSignedHeaders(List<WSEncryptionPart> encryptedParts, 
            List<WSEncryptionPart> signedParts) {

        final List<WSEncryptionPart> signedEncryptedParts = new ArrayList<WSEncryptionPart>();
        
        for (WSEncryptionPart encryptedPart : encryptedParts) {
            final Iterator<WSEncryptionPart> signedPartsIt = signedParts.iterator();
            while (signedPartsIt.hasNext()) {
                WSEncryptionPart signedPart = signedPartsIt.next();
                // Everything has to be ID based except for the case of a part
                // indicating "Token" as the element name.  This name is a flag
                // for WSS4J to sign the initiator token used in the signature.
                // Since the encryption happened before the signature creation,
                // this element can't possibly be encrypted so we can safely ignore
                // if it were ever to be set before this method is called.
                if (signedPart.getId() == null && !"Token".equals(signedPart.getName())) {
                    throw new IllegalArgumentException(
                            "WSEncryptionPart must be ID based but no id was found.");
                } else if (encryptedPart.getEncModifier().equals("Element")
                        && signedPart.getId().equals(encryptedPart.getId())) {
                    // We are to sign something that has already been encrypted.
                    // We need to preserve the original aspects of signedPart but
                    // change the ID to the encrypted ID.
                    
                    signedPartsIt.remove();
                    WSEncryptionPart part = new WSEncryptionPart(
                            encryptedPart.getEncId(),
                            encryptedPart.getEncModifier());
                    part.setElement(encryptedPart.getElement());
                    signedEncryptedParts.add(part);
                }
            }
        }
        
        signedParts.addAll(signedEncryptedParts);
    }
 
    /**
     * Convert a DOM Element into a WSEncryptionPart, adding a (wsu:)Id if there is not
     * one already.
     * @param element The DOM Element to convert
     * @return The WSEncryptionPart representing the DOM Element argument
     */
    public WSEncryptionPart convertToEncryptionPart(Element element) {
        String id = addWsuIdToElement(element);
        WSEncryptionPart part = new WSEncryptionPart(id);
        part.setElement(element);
        return part;
    }
    
    
}
