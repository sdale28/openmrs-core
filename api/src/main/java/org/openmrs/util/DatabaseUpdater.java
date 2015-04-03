/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.util;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import liquibase.Liquibase;
import liquibase.changelog.ChangeLogIterator;
import liquibase.changelog.ChangeLogParameters;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.DatabaseChangeLog;
import liquibase.changelog.filter.ContextChangeSetFilter;
import liquibase.changelog.filter.DbmsChangeSetFilter;
import liquibase.changelog.filter.ShouldRunChangeSetFilter;
import liquibase.changelog.visitor.UpdateVisitor;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.exception.LiquibaseException;
import liquibase.exception.LockException;
import liquibase.lockservice.LockService;
import liquibase.parser.core.xml.XMLChangeLogSAXParser;
import liquibase.resource.CompositeResourceAccessor;
import liquibase.resource.FileSystemResourceAccessor;
import liquibase.resource.ResourceAccessor;

import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.openmrs.annotation.Authorized;
import org.openmrs.api.context.Context;

/**
 * This class uses Liquibase to update the database. <br/>
 * <br/>
 * See /metadata/model/liquibase-update-to-latest.xml for the changes. This class will also run
 * arbitrary liquibase xml files on the associated database as well. Details for the database are
 * taken from the openmrs runtime properties.
 *
 * @since 1.5
 */
public class DatabaseUpdater {
	
	private static final Log log = LogFactory.getLog(DatabaseUpdater.class);
	
	private static final String CHANGE_LOG_FILE = "liquibase-update-to-latest.xml";
	
	private static final String CONTEXT = "core";
	
	public static final String DATABASE_UPDATES_LOG_FILE = "liquibaseUpdateLogs.txt";
	
	private static Integer authenticatedUserId;
	
	/**
	 * Holds the update warnings generated by the custom liquibase changesets as they are executed
	 */
	private static volatile List<String> updateWarnings = null;
	
	/**
	 * Convenience method to run the changesets using Liquibase to bring the database up to a
	 * version compatible with the code
	 *
	 * @throws InputRequiredException if the changelog file requirest some sort of user input. The
	 *             error object will list of the user prompts and type of data for each prompt
	 * @see #update(Map)
	 * @see #executeChangelog(String, Map)
	 */
	public static void executeChangelog() throws DatabaseUpdateException, InputRequiredException {
		executeChangelog(null, null);
	}
	
	/**
	 * Run changesets on database using Liquibase to get the database up to the most recent version
	 *
	 * @param changelog the liquibase changelog file to use (or null to use the default file)
	 * @param userInput nullable map from question to user answer. Used if a call to update(null)
	 *            threw an {@link InputRequiredException}
	 * @throws DatabaseUpdateException
	 * @throws InputRequiredException
	 */
	public static void executeChangelog(String changelog, Map<String, Object> userInput) throws DatabaseUpdateException,
	        InputRequiredException {
		
		log.debug("Executing changelog: " + changelog);
		
		executeChangelog(changelog, userInput, null);
	}
	
	/**
	 * Interface used for callbacks when updating the database. Implement this interface and pass it
	 * to {@link DatabaseUpdater#executeChangelog(String, Map, ChangeSetExecutorCallback)}
	 */
	public interface ChangeSetExecutorCallback {
		
		/**
		 * This method is called after each changeset is executed.
		 *
		 * @param changeSet the liquibase changeset that was just run
		 * @param numChangeSetsToRun the total number of changesets in the current file
		 */
		public void executing(ChangeSet changeSet, int numChangeSetsToRun);
	}
	
	/**
	 * Executes the given changelog file. This file is assumed to be on the classpath. If no file is
	 * given, the default {@link #CHANGE_LOG_FILE} is ran.
	 *
	 * @param changelog The string filename of a liquibase changelog xml file to run
	 * @param userInput nullable map from question to user answer. Used if a call to
	 *            executeChangelog(<String>, null) threw an {@link InputRequiredException}
	 * @return A list of messages or warnings generated by the executed changesets
	 * @throws InputRequiredException if the changelog file requirest some sort of user input. The
	 *             error object will list of the user prompts and type of data for each prompt
	 */
	public static List<String> executeChangelog(String changelog, Map<String, Object> userInput,
	        ChangeSetExecutorCallback callback) throws DatabaseUpdateException, InputRequiredException {
		log.debug("installing the tables into the database");
		
		if (changelog == null) {
			changelog = CHANGE_LOG_FILE;
		}
		
		try {
			return executeChangelog(changelog, CONTEXT, userInput, callback);
		}
		catch (Exception e) {
			throw new DatabaseUpdateException("There was an error while updating the database to the latest. file: "
			        + changelog + ". Error: " + e.getMessage(), e);
		}
	}
	
	/**
	 * @deprecated use
	 *             {@link #executeChangelog(String, String, Map, ChangeSetExecutorCallback, ClassLoader)}
	 */
	@Deprecated
	public static List<String> executeChangelog(String changeLogFile, String contexts, Map<String, Object> userInput,
	        ChangeSetExecutorCallback callback) throws Exception {
		return executeChangelog(changeLogFile, contexts, userInput, callback, null);
	}
	
	/**
	 * This code was borrowed from the liquibase jar so that we can call the given callback
	 * function.
	 *
	 * @param changeLogFile the file to execute
	 * @param contexts the liquibase changeset context
	 * @param userInput answers given by the user
	 * @param callback the function to call after every changeset
	 * @param cl {@link ClassLoader} to use to find the changeLogFile (or null to use
	 *            {@link OpenmrsClassLoader})
	 * @return A list of messages or warnings generated by the executed changesets
	 * @throws Exception
	 */
	public static List<String> executeChangelog(String changeLogFile, String contexts, Map<String, Object> userInput,
	        ChangeSetExecutorCallback callback, ClassLoader cl) throws Exception {
		final class OpenmrsUpdateVisitor extends UpdateVisitor {
			
			private ChangeSetExecutorCallback callback;
			
			private int numChangeSetsToRun;
			
			public OpenmrsUpdateVisitor(Database database, ChangeSetExecutorCallback callback, int numChangeSetsToRun) {
				super(database);
				this.callback = callback;
				this.numChangeSetsToRun = numChangeSetsToRun;
			}
			
			@Override
			public void visit(ChangeSet changeSet, DatabaseChangeLog databaseChangeLog, Database database)
			        throws LiquibaseException {
				if (callback != null) {
					callback.executing(changeSet, numChangeSetsToRun);
				}
				super.visit(changeSet, databaseChangeLog, database);
			}
		}
		
		if (cl == null) {
			cl = OpenmrsClassLoader.getInstance();
		}
		
		log.debug("Setting up liquibase object to run changelog: " + changeLogFile);
		Liquibase liquibase = getLiquibase(changeLogFile, cl);
		int numChangeSetsToRun = liquibase.listUnrunChangeSets(contexts).size();
		Database database = null;
		LockService lockHandler = null;
		
		try {
			database = liquibase.getDatabase();
			lockHandler = LockService.getInstance(database);
			lockHandler.waitForLock();
			
			ResourceAccessor openmrsFO = new ClassLoaderFileOpener(cl);
			ResourceAccessor fsFO = new FileSystemResourceAccessor();
			
			DatabaseChangeLog changeLog = new XMLChangeLogSAXParser().parse(changeLogFile, new ChangeLogParameters(),
			    new CompositeResourceAccessor(openmrsFO, fsFO));
			changeLog.setChangeLogParameters(liquibase.getChangeLogParameters());
			changeLog.validate(database);
			ChangeLogIterator logIterator = new ChangeLogIterator(changeLog, new ShouldRunChangeSetFilter(database),
			        new ContextChangeSetFilter(contexts), new DbmsChangeSetFilter(database));
			database.checkDatabaseChangeLogTable(true, changeLog, new String[] { contexts });
			logIterator.run(new OpenmrsUpdateVisitor(database, callback, numChangeSetsToRun), database);
		}
		catch (LiquibaseException e) {
			throw e;
		}
		finally {
			try {
				lockHandler.releaseLock();
			}
			catch (Exception e) {
				log.error("Could not release lock", e);
			}
			try {
				database.getConnection().close();
			}
			catch (Exception e) {
				//pass
			}
		}
		
		return updateWarnings;
	}
	
	/**
	 * Ask Liquibase if it needs to do any updates. Only looks at the {@link #CHANGE_LOG_FILE}
	 *
	 * @return true/false whether database updates are required
	 * @should always have a valid update to latest file
	 */
	public static boolean updatesRequired() throws Exception {
		log.debug("checking for updates");
		List<OpenMRSChangeSet> changesets = getUnrunDatabaseChanges();
		
		// if the db is locked, it means there was a crash
		// or someone is executing db updates right now. either way
		// returning true here stops the openmrs startup and shows
		// the user the maintenance wizard for updates
		if (isLocked() && changesets.size() == 0) {
			// if there is a db lock but there are no db changes we undo the
			// lock
			DatabaseUpdater.releaseDatabaseLock();
			log.debug("db lock found and released automatically");
			return false;
		}
		
		return changesets.size() > 0;
	}
	
	/**
	 * Ask Liquibase if it needs to do any updates
	 *
	 * @param changeLogFilenames the filenames of all files to search for unrun changesets
	 * @return true/false whether database updates are required
	 * @should always have a valid update to latest file
	 */
	public static boolean updatesRequired(String... changeLogFilenames) throws Exception {
		log.debug("checking for updates");
		
		List<OpenMRSChangeSet> changesets = getUnrunDatabaseChanges(changeLogFilenames);
		return changesets.size() > 0;
	}
	
	/**
	 * Indicates whether automatic database updates are allowed by this server. Automatic updates
	 * are disabled by default. In order to enable automatic updates, the admin needs to add
	 * 'auto_update_database=true' to the runtime properties file.
	 *
	 * @return true/false whether the 'auto_update_database' has been enabled.
	 */
	public static Boolean allowAutoUpdate() {
		String allowAutoUpdate = Context.getRuntimeProperties().getProperty(
		    OpenmrsConstants.AUTO_UPDATE_DATABASE_RUNTIME_PROPERTY, "false");
		
		return "true".equals(allowAutoUpdate);
		
	}
	
	/**
	 * Takes the default properties defined in /metadata/api/hibernate/hibernate.default.properties
	 * and merges it into the user-defined runtime properties
	 *
	 * @see org.openmrs.api.db.ContextDAO#mergeDefaultRuntimeProperties(java.util.Properties)
	 */
	private static void mergeDefaultRuntimeProperties(Properties runtimeProperties) {
		
		// loop over runtime properties and precede each with "hibernate" if
		// it isn't already
		Set<Object> runtimePropertyKeys = new HashSet<Object>();
		runtimePropertyKeys.addAll(runtimeProperties.keySet()); // must do it this way to prevent concurrent mod errors
		for (Object key : runtimePropertyKeys) {
			String prop = (String) key;
			String value = (String) runtimeProperties.get(key);
			log.trace("Setting property: " + prop + ":" + value);
			if (!prop.startsWith("hibernate") && !runtimeProperties.containsKey("hibernate." + prop)) {
				runtimeProperties.setProperty("hibernate." + prop, value);
			}
		}
		
		// load in the default hibernate properties from hibernate.default.properties
		InputStream propertyStream = null;
		try {
			Properties props = new Properties();
			// TODO: This is a dumb requirement to have hibernate in here.  Clean this up
			propertyStream = DatabaseUpdater.class.getClassLoader().getResourceAsStream("hibernate.default.properties");
			OpenmrsUtil.loadProperties(props, propertyStream);
			// add in all default properties that don't exist in the runtime
			// properties yet
			for (Map.Entry<Object, Object> entry : props.entrySet()) {
				if (!runtimeProperties.containsKey(entry.getKey())) {
					runtimeProperties.put(entry.getKey(), entry.getValue());
				}
			}
		}
		finally {
			try {
				propertyStream.close();
			}
			catch (Exception e) {
				// pass
			}
		}
	}
	
	/**
	 * Get a connection to the database through Liquibase. The calling method /must/ close the
	 * database connection when finished with this Liquibase object.
	 * liquibase.getDatabase().getConnection().close()
	 *
	 * @param changeLogFile the name of the file to look for the on classpath or filesystem
	 * @param cl the {@link ClassLoader} to use to find the file (or null to use
	 *            {@link OpenmrsClassLoader})
	 * @return Liquibase object based on the current connection settings
	 * @throws Exception
	 */
	private static Liquibase getLiquibase(String changeLogFile, ClassLoader cl) throws Exception {
		Connection connection = null;
		try {
			connection = getConnection();
		}
		catch (SQLException e) {
			throw new Exception(
			        "Unable to get a connection to the database.  Please check your openmrs runtime properties file and make sure you have the correct connection.username and connection.password set",
			        e);
		}
		
		if (cl == null) {
			cl = OpenmrsClassLoader.getInstance();
		}
		
		try {
			Database database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(
			    new JdbcConnection(connection));
			database.setDatabaseChangeLogTableName("liquibasechangelog");
			database.setDatabaseChangeLogLockTableName("liquibasechangeloglock");
			
			if (connection.getMetaData().getDatabaseProductName().contains("HSQL Database Engine")
			        || connection.getMetaData().getDatabaseProductName().contains("H2")) {
				// a hack because hsqldb and h2 seem to be checking table names in the metadata section case sensitively
				database.setDatabaseChangeLogTableName(database.getDatabaseChangeLogTableName().toUpperCase());
				database.setDatabaseChangeLogLockTableName(database.getDatabaseChangeLogLockTableName().toUpperCase());
			}
			
			ResourceAccessor openmrsFO = new ClassLoaderFileOpener(cl);
			ResourceAccessor fsFO = new FileSystemResourceAccessor();
			
			if (changeLogFile == null) {
				changeLogFile = CHANGE_LOG_FILE;
			}
			
			database.checkDatabaseChangeLogTable(false, null, null);
			
			return new Liquibase(changeLogFile, new CompositeResourceAccessor(openmrsFO, fsFO), database);
		}
		catch (Exception e) {
			// if an error occurs, close the connection
			if (connection != null) {
				connection.close();
			}
			throw e;
		}
	}
	
	/**
	 * Gets a database connection for liquibase to do the updates
	 *
	 * @return a java.sql.connection based on the current runtime properties
	 */
	public static Connection getConnection() throws Exception {
		Properties props = Context.getRuntimeProperties();
		mergeDefaultRuntimeProperties(props);
		
		String driver = props.getProperty("hibernate.connection.driver_class");
		String username = props.getProperty("hibernate.connection.username");
		String password = props.getProperty("hibernate.connection.password");
		String url = props.getProperty("hibernate.connection.url");
		
		// hack for mysql to make sure innodb tables are created
		if (url.contains("mysql") && !url.contains("InnoDB")) {
			url = url + "&sessionVariables=storage_engine=InnoDB";
		}
		
		Class.forName(driver);
		return DriverManager.getConnection(url, username, password);
	}
	
	/**
	 * Represents each change in the liquibase-update-to-latest
	 */
	public static class OpenMRSChangeSet {
		
		private String id;
		
		private String author;
		
		private String comments;
		
		private String description;
		
		private ChangeSet.RunStatus runStatus;
		
		private Date ranDate;
		
		/**
		 * Create an OpenmrsChangeSet from the given changeset
		 *
		 * @param changeSet
		 * @param database
		 */
		public OpenMRSChangeSet(ChangeSet changeSet, Database database) throws Exception {
			setId(changeSet.getId());
			setAuthor(changeSet.getAuthor());
			setComments(changeSet.getComments());
			setDescription(changeSet.getDescription());
			setRunStatus(database.getRunStatus(changeSet));
			setRanDate(database.getRanDate(changeSet));
		}
		
		/**
		 * @return the author
		 */
		public String getAuthor() {
			return author;
		}
		
		/**
		 * @param author the author to set
		 */
		public void setAuthor(String author) {
			this.author = author;
		}
		
		/**
		 * @return the comments
		 */
		public String getComments() {
			return comments;
		}
		
		/**
		 * @param comments the comments to set
		 */
		public void setComments(String comments) {
			this.comments = comments;
		}
		
		/**
		 * @return the description
		 */
		public String getDescription() {
			return description;
		}
		
		/**
		 * @param description the description to set
		 */
		public void setDescription(String description) {
			this.description = description;
		}
		
		/**
		 * @return the runStatus
		 */
		public ChangeSet.RunStatus getRunStatus() {
			return runStatus;
		}
		
		/**
		 * @param runStatus the runStatus to set
		 */
		public void setRunStatus(ChangeSet.RunStatus runStatus) {
			this.runStatus = runStatus;
		}
		
		/**
		 * @return the ranDate
		 */
		public Date getRanDate() {
			return ranDate;
		}
		
		/**
		 * @param ranDate the ranDate to set
		 */
		public void setRanDate(Date ranDate) {
			this.ranDate = ranDate;
		}
		
		/**
		 * @return the id
		 */
		public String getId() {
			return id;
		}
		
		/**
		 * @param id the id to set
		 */
		public void setId(String id) {
			this.id = id;
		}
		
	}
	
	/**
	 * Looks at the current liquibase-update-to-latest.xml file and then checks the database to see
	 * if they have been run.
	 *
	 * @return list of changesets that both have and haven't been run
	 */
	@Authorized(PrivilegeConstants.VIEW_DATABASE_CHANGES)
	public static List<OpenMRSChangeSet> getDatabaseChanges() throws Exception {
		Database database = null;
		
		try {
			Liquibase liquibase = getLiquibase(CHANGE_LOG_FILE, null);
			database = liquibase.getDatabase();
			DatabaseChangeLog changeLog = new XMLChangeLogSAXParser().parse(CHANGE_LOG_FILE, new ChangeLogParameters(),
			    liquibase.getFileOpener());
			List<ChangeSet> changeSets = changeLog.getChangeSets();
			
			List<OpenMRSChangeSet> results = new ArrayList<OpenMRSChangeSet>();
			for (ChangeSet changeSet : changeSets) {
				OpenMRSChangeSet omrschangeset = new OpenMRSChangeSet(changeSet, database);
				results.add(omrschangeset);
			}
			
			return results;
		}
		finally {
			try {
				if (database != null) {
					database.getConnection().close();
				}
			}
			catch (Exception e) {
				//pass
			}
		}
	}
	
	/**
	 * @see DatabaseUpdater#getUnrunDatabaseChanges(String...)
	 */
	@Authorized(PrivilegeConstants.VIEW_DATABASE_CHANGES)
	public static List<OpenMRSChangeSet> getUnrunDatabaseChanges() throws Exception {
		return getUnrunDatabaseChanges(CHANGE_LOG_FILE);
	}
	
	/**
	 * Looks at the specified liquibase change log files and returns all changesets in the files
	 * that have not been run on the database yet. If no argument is specified, then it looks at the
	 * current liquibase-update-to-latest.xml file
	 *
	 * @param changeLogFilenames the filenames of all files to search for unrun changesets
	 * @return
	 * @throws Exception
	 */
	@Authorized(PrivilegeConstants.VIEW_DATABASE_CHANGES)
	public static List<OpenMRSChangeSet> getUnrunDatabaseChanges(String... changeLogFilenames) throws Exception {
		log.debug("Getting unrun changesets");
		
		Database database = null;
		try {
			if (changeLogFilenames == null) {
				throw new IllegalArgumentException("changeLogFilenames cannot be null");
			}
			
			//if no argument, look ONLY in liquibase-update-to-latest.xml
			if (changeLogFilenames.length == 0) {
				changeLogFilenames = new String[] { CHANGE_LOG_FILE };
			}
			
			List<OpenMRSChangeSet> results = new ArrayList<OpenMRSChangeSet>();
			for (String changelogFile : changeLogFilenames) {
				Liquibase liquibase = getLiquibase(changelogFile, null);
				database = liquibase.getDatabase();
				List<ChangeSet> changeSets = liquibase.listUnrunChangeSets(CONTEXT);
				
				for (ChangeSet changeSet : changeSets) {
					OpenMRSChangeSet omrschangeset = new OpenMRSChangeSet(changeSet, database);
					results.add(omrschangeset);
				}
			}
			
			return results;
			
		}
		catch (Exception e) {
			throw new RuntimeException("Error occurred while trying to get the updates needed for the database. "
			        + e.getMessage(), e);
		}
		finally {
			try {
				database.getConnection().close();
			}
			catch (Exception e) {
				//pass
			}
		}
	}
	
	/**
	 * @return the authenticatedUserId
	 */
	public static Integer getAuthenticatedUserId() {
		return authenticatedUserId;
	}
	
	/**
	 * @param authenticatedUserId the authenticatedUserId to set
	 */
	public static void setAuthenticatedUserId(Integer userId) {
		authenticatedUserId = userId;
	}
	
	/**
	 * This method is called by an executing custom changeset to register warning messages.
	 *
	 * @param warnings list of warnings to append to the end of the current list
	 */
	public static void reportUpdateWarnings(List<String> warnings) {
		if (updateWarnings == null) {
			updateWarnings = new LinkedList<String>();
		}
		updateWarnings.addAll(warnings);
	}
	
	/**
	 * This method writes the given text to the database updates log file located in the application
	 * data directory.
	 *
	 * @param the text to be written to the file
	 */
	public static void writeUpdateMessagesToFile(String text) {
		PrintWriter writer = null;
		File destFile = new File(OpenmrsUtil.getApplicationDataDirectory(), DatabaseUpdater.DATABASE_UPDATES_LOG_FILE);
		try {
			String lineSeparator = System.getProperty("line.separator");
			Date date = Calendar.getInstance().getTime();
			
			writer = new PrintWriter(new BufferedWriter(new FileWriter(destFile, true)));
			writer.write("********** START OF DATABASE UPDATE LOGS AS AT " + date + " **********");
			writer.write(lineSeparator);
			writer.write(lineSeparator);
			writer.write(text);
			writer.write(lineSeparator);
			writer.write(lineSeparator);
			writer.write("*********** END OF DATABASE UPDATE LOGS AS AT " + date + " ***********");
			writer.write(lineSeparator);
			writer.write(lineSeparator);
			
			//check if there was an error while writing to the file
			if (writer.checkError()) {
				log.warn("An Error occured while writing warnings to the database update log file'");
			}
			
			writer.close();
		}
		catch (FileNotFoundException e) {
			log.warn("Failed to find the database update log file", e);
		}
		catch (IOException e) {
			log.warn("Failed to write to the database update log file", e);
		}
		finally {
			IOUtils.closeQuietly(writer);
		}
	}
	
	/**
	 * This method releases the liquibase db lock after a crashed database update. First, it
	 * checks whether "liquibasechangeloglock" table exists in db. If so, it will check
	 * whether the database is locked. If thats also true, this means that last attempted db
	 * update crashed.<br/>
	 * <br/>
	 * This should only be called if the user is sure that no one else is currently running
	 * database updates. This method should be used if there was a db crash while updates
	 * were being written and the lock table was never cleaned up.
	 *
	 * @throws LockException
	 */
	public static synchronized void releaseDatabaseLock() throws LockException {
		Database database = null;
		
		try {
			Liquibase liquibase = getLiquibase(null, null);
			database = liquibase.getDatabase();
			if (database.hasDatabaseChangeLogLockTable() && isLocked()) {
				LockService.getInstance(database).forceReleaseLock();
			}
		}
		catch (Exception e) {
			throw new LockException(e);
		}
		finally {
			try {
				database.getConnection().close();
			}
			catch (Exception e) {
				// pass
			}
		}
	}
	
	/**
	 * This method currently checks the liquibasechangeloglock table to see if there is a row
	 * with a lock in it.  This uses the liquibase API to do this
	 *
	 * @return true if database is currently locked
	 */
	public static boolean isLocked() {
		Database database = null;
		try {
			Liquibase liquibase = getLiquibase(null, null);
			database = liquibase.getDatabase();
			return LockService.getInstance(database).listLocks().length > 0;
		}
		catch (Exception e) {
			return false;
		}
		finally {
			try {
				database.getConnection().close();
			}
			catch (Exception e) {
				// pass
			}
		}
	}
}
