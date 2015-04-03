/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.attribute;

import org.openmrs.BaseOpenmrsMetadata;
import org.openmrs.customdatatype.Customizable;

/**
 * Abstract base implementation of {@link AttributeType}. Actual implementations (e.g. VisitAttributeType,
 * ProviderAttributeType) should be able to extend this and provide very little of their own code.
 * @since 1.9
 */
public abstract class BaseAttributeType<OwningType extends Customizable<?>> extends BaseOpenmrsMetadata implements AttributeType<OwningType> {
	
	private Integer minOccurs = 0;
	
	private Integer maxOccurs = null;
	
	private String datatypeClassname;
	
	private String datatypeConfig;
	
	private String preferredHandlerClassname;
	
	private String handlerConfig;
	
	/**
	 * @see org.openmrs.customdatatype.RepeatingCustomValueDescriptor#getMinOccurs()
	 */
	@Override
	public Integer getMinOccurs() {
		return minOccurs;
	}
	
	/**
	 * @see org.openmrs.customdatatype.RepeatingCustomValueDescriptor#getMaxOccurs()
	 */
	@Override
	public Integer getMaxOccurs() {
		return maxOccurs;
	}
	
	/**
	 * @see org.openmrs.customdatatype.CustomValueDescriptor#getDatatypeClassname()
	 */
	@Override
	public String getDatatypeClassname() {
		return datatypeClassname;
	}
	
	/**
	 * @see org.openmrs.customdatatype.CustomValueDescriptor#getDatatypeConfig()
	 */
	@Override
	public String getDatatypeConfig() {
		return datatypeConfig;
	}
	
	/**
	 * @see org.openmrs.customdatatype.CustomValueDescriptor#getPreferredHandlerClassname()
	 */
	@Override
	public String getPreferredHandlerClassname() {
		return preferredHandlerClassname;
	}
	
	/**
	 * @see org.openmrs.customdatatype.CustomValueDescriptor#getHandlerConfig()
	 */
	@Override
	public String getHandlerConfig() {
		return handlerConfig;
	}
	
	/**
	 * @param minOccurs the minOccurs to set
	 */
	public void setMinOccurs(Integer minOccurs) {
		this.minOccurs = minOccurs;
	}
	
	/**
	 * @param maxOccurs the maxOccurs to set
	 */
	public void setMaxOccurs(Integer maxOccurs) {
		this.maxOccurs = maxOccurs;
	}
	
	/**
	 * @param datatypeClassname the datatypeClassname to set
	 */
	public void setDatatypeClassname(String datatypeClassname) {
		this.datatypeClassname = datatypeClassname;
	}
	
	/**
	 * @param datatypeConfig the datatypeConfig to set
	 */
	public void setDatatypeConfig(String datatypeConfig) {
		this.datatypeConfig = datatypeConfig;
	}
	
	/**
	 * @param preferredHandlerClassname the preferredHandlerClassname to set
	 */
	public void setPreferredHandlerClassname(String preferredHandlerClassname) {
		this.preferredHandlerClassname = preferredHandlerClassname;
	}
	
	/**
	 * @param handlerConfig the handlerConfig to set
	 */
	public void setHandlerConfig(String handlerConfig) {
		this.handlerConfig = handlerConfig;
	}
	
}
