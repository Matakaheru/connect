/* ***** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Original Code is Mirth.
 *
 * The Initial Developer of the Original Code is
 * WebReach, Inc.
 * Portions created by the Initial Developer are Copyright (C) 2006
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s):
 *   Gerald Bortis <geraldb@webreachinc.com>
 *
 * ***** END LICENSE BLOCK ***** */


package com.webreach.mirth.server.launcher;

import java.io.File;
import java.io.FileFilter;
import java.net.URL;
import java.util.ArrayList;

import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.log4j.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

public class ClasspathBuilder {
	private Logger logger = Logger.getLogger(this.getClass());
	private String launcherFileName = null;
	
	public ClasspathBuilder(String launcherFileName) {
		this.launcherFileName = launcherFileName;
	}

	public URL[] getClasspath() throws Exception {
		FileFilter pathFileFilter = new FileFilter() {
			public boolean accept(File file) {
				return (!file.isDirectory());
			}
		};

		ArrayList<URL> urls = new ArrayList<URL>();
		File launcherFile = new File(launcherFileName);
		
		if (launcherFile.exists()) {
			Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(launcherFile);
			Element launcherElement = document.getDocumentElement();
			Element classpathElement = (Element) launcherElement.getElementsByTagName("classpath").item(0);
			
			for (int i = 0; i < classpathElement.getElementsByTagName("lib").getLength(); i++) {
				String base = classpathElement.getAttribute("base");
				Element pathElement = (Element) classpathElement.getElementsByTagName("lib").item(i);
				File path = new File(base + "/" + pathElement.getAttribute("path"));
				
				if (path.exists()) {
					if (path.isDirectory()) {
						File[] pathFiles = path.listFiles(pathFileFilter);

						for (int j = 0; j < pathFiles.length; j++) {
							logger.trace("adding file to path: " + pathFiles[j].getAbsolutePath());
							urls.add(pathFiles[j].toURI().toURL());
						}
					} else {
						logger.trace("adding file to path: " + path.getAbsolutePath());
						urls.add(path.toURI().toURL());
					}
				} else {
					logger.warn("Could not locate path: " + path.getAbsolutePath());
				}
			}
		} else {
			logger.error("Could not locate launcher file:" + launcherFile.getAbsolutePath());
		}

		return urls.toArray(new URL[urls.size()]);
	}

}
