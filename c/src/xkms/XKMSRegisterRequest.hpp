/*
 * Copyright 2004-2005 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * XSEC
 *
 * XKMSRegisterRequest := Interface for RegisterRequest Messages
 *
 * $Id$
 *
 */

#ifndef XKMSREGISTERREQUEST_INCLUDE
#define XKMSREGISTERREQUEST_INCLUDE

// XSEC Includes

#include <xsec/framework/XSECDefs.hpp>
#include <xsec/xkms/XKMSRequestAbstractType.hpp>

class DSIGSignature;
class XKMSAuthentication;
class XKMSPrototypeKeyBinding;

/**
 * @ingroup xkms
 * @{
 */

/**
 * @brief Interface definition for the RegisterRequest elements
 *
 * The \<RegisterRequest\> is one of the fundamental message types of
 * the X-KRMS service.  It is used by a client wishing to register a
 * key with a service.
 *
 * The schema definition for RegisterRequest is as follows :
 *
 * \verbatim
   <!-- RegisterRequest -->
   <element name="RegisterRequest" type="xkms:RegisterRequestType"/>
   <complexType name="RegisterRequestType">
      <complexContent>
         <extension base="xkms:RequestAbstractType">
            <sequence>
               <element ref="xkms:PrototypeKeyBinding"/>
               <element ref="xkms:Authentication"/>
               <element ref="xkms:ProofOfPossession" minOccurs="0"/>
            </sequence>
         </extension>
      </complexContent>
   </complexType>
   <!-- /RegisterRequest -->
\endverbatim
 */

class XKMSRegisterRequest : virtual public XKMSRequestAbstractType {

	/** @name Constructors and Destructors */
	//@{

protected:

	XKMSRegisterRequest() {};

public:

	virtual ~XKMSRegisterRequest() {};

	/** @name Getter Interface Methods */
	//@{

	/**
	 * \brief Return the element at the base of the message
	 */

	virtual XERCES_CPP_NAMESPACE_QUALIFIER DOMElement * getElement(void) const = 0;

	/**
	 * \brief Obtain the PrototypKeyBinding element
	 *
	 * The PrototypeKeyBinding element is the core of the RegisterRequest message, and
	 * defines the key information that will be sent to the server for registration.
	 *
	 * @returns A pointer to the XKMSPrototypeKeyBinding element
	 */

	virtual XKMSPrototypeKeyBinding * getPrototypeKeyBinding(void) const = 0;

	/**
	 * \brief Get the Authentication element
	 *
	 * The Authentication element of the RegisterRequest is used by the client to
	 * authenticate the request to the server.
	 *
	 * @return A pointer to the Authentication structure 
	 */

	virtual XKMSAuthentication * getAuthentication (void) const = 0;

	/**
	 * \brief Get the signature used to prove possession of the private key
	 *
	 * When the client presents a request for a key generated by them, this element
	 * is used to show that the client is authorised to make this request using this
	 * key.
	 *
	 * @return A pointer to the proof of possession Signature object (or NULL if none
	 * was defined
	 */

	virtual DSIGSignature * getProofOfPossessionSignature(void) const = 0;

	//@}

	/** @name Setter Interface Methods */
	//@{

	/** \brief Add a PrototypeKeyBinding element
	 *
	 * Set a PrototypeKeyBinding element in the Request message.  The returned
	 * object can be manipulated to add KeyInfo elements to the Request.
	 *
	 * @returns A pointer to the newly created PrototypeKeyBinding object, or
	 * the pointer to extant object if one already existed.
	 */

	virtual XKMSPrototypeKeyBinding * addPrototypeKeyBinding(void) = 0;

	/** \brief Add an Authentication element
	 *
	 * Set a Authentication element in the Request message.  The returned
	 * object can be manipulated to add Authentication information to the request.
	 *
	 * @returns A pointer to the newly created Authenticaton object, or
	 * the pointer to extant object if one already existed.
	 */

	virtual XKMSAuthentication * addAuthentication(void) = 0;

	/**
	 * \brief Add a ProofOfPossession signature to the message
	 *
	 * Allows the application to add a new ProofOfPossession signature into a 
	 * RegisterRequest element
	 *
	 * @note the client application will need to set the key and sign the
	 * message - however the appropriate reference (to the PrototypeKeyBinding
	 * element) will be set.  This implies that the PrototypeKeyBinding *must*
	 * be added prior to the call to this method.
	 *
	 * @returns the new Signature structure
	 */

	virtual DSIGSignature * addProofOfPossessionSignature(
		canonicalizationMethod cm = CANON_C14N_NOC,
		signatureMethod	sm = SIGNATURE_DSA,
		hashMethod hm = HASH_SHA1) = 0;
	
	//@}

private:

	// Unimplemented
	XKMSRegisterRequest(const XKMSRegisterRequest &);
	XKMSRegisterRequest & operator = (const XKMSRegisterRequest &);

};

#endif /* XKMSREGISTERREQUEST_INCLUDE */
