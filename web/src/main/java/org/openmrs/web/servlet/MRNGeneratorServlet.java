/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.web.servlet;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.openmrs.api.AdministrationService;
import org.openmrs.api.context.Context;
import org.openmrs.util.OpenmrsUtil;
import org.openmrs.web.WebConstants;

public class MRNGeneratorServlet extends HttpServlet {
	
	/**
	 * TODO Where to put this (mostly) AMRS-specific servlet ?
	 */
	
	public static final long serialVersionUID = 1231231L;
	
	protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		
		String site = request.getParameter("site");
		String prefix = request.getParameter("mrn_prefix");
		String first = request.getParameter("mrn_first");
		String count = request.getParameter("mrn_count");
		HttpSession session = request.getSession();
		
		if (prefix == null) {
			prefix = "";
		}
		
		if (site == null || first == null || count == null || site.length() == 0 || first.length() == 0
		        || count.length() == 0) {
			session.setAttribute(WebConstants.OPENMRS_ERROR_ATTR, "MRNGenerator.all.required");
			response.sendRedirect("admin/maintenance/mrnGenerator.htm");
			return;
		}
		
		Integer mrnFirst = Integer.valueOf(first);
		Integer mrnCount = Integer.valueOf(count);
		
		AdministrationService as = Context.getAdministrationService();
		
		// log who generated this list
		as.mrnGeneratorLog(site, mrnFirst, mrnCount);
		
		String filename = site + "_" + mrnFirst + "-" + (mrnFirst + (mrnCount - 1)) + prefix + ".txt";
		
		response.setHeader("Content-Type", "text");
		response.setHeader("Content-Disposition", "attachment; filename=" + filename);
		
		Integer end = mrnCount + mrnFirst;
		while (mrnFirst < end) {
			
			StringBuilder line = new StringBuilder(prefix).append(mrnFirst).append(site);
			int checkdigit;
			try {
				checkdigit = OpenmrsUtil.getCheckDigit(line.toString());
			}
			catch (Exception e) {
				throw new ServletException(e);
			}
			line.append("-").append(checkdigit);
			
			response.getOutputStream().println(line.toString());
			
			mrnFirst++;
		}
	}
}
