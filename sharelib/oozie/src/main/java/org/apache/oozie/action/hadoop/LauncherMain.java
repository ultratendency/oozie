/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.oozie.action.hadoop;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.StringWriter;
import java.net.URL;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.util.Shell;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;

import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.yarn.api.ApplicationClientProtocol;
import org.apache.hadoop.yarn.api.protocolrecords.ApplicationsRequestScope;
import org.apache.hadoop.yarn.api.protocolrecords.GetApplicationsRequest;
import org.apache.hadoop.yarn.api.protocolrecords.GetApplicationsResponse;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ApplicationReport;
import org.apache.hadoop.yarn.client.ClientRMProxy;
import org.apache.hadoop.yarn.client.api.YarnClient;
import org.apache.hadoop.yarn.exceptions.YarnException;

public abstract class LauncherMain {

    public static final String ACTION_PREFIX = "oozie.action.";
    public static final String EXTERNAL_CHILD_IDS = ACTION_PREFIX + "externalChildIDs";
    public static final String EXTERNAL_ACTION_STATS = ACTION_PREFIX + "stats.properties";
    public static final String EXTERNAL_STATS_WRITE = ACTION_PREFIX + "external.stats.write";
    public static final String OUTPUT_PROPERTIES = ACTION_PREFIX + "output.properties";
    public static final String HADOOP_JOBS = "hadoopJobs";
    public static final String MAPREDUCE_JOB_TAGS = "mapreduce.job.tags";

    public static final String CHILD_MAPREDUCE_JOB_TAGS = "oozie.child.mapreduce.job.tags";
    public static final String OOZIE_JOB_LAUNCH_TIME = "oozie.job.launch.time";

    public static final String TEZ_APPLICATION_TAGS = "tez.application.tags";
    public static final String SPARK_YARN_TAGS = "spark.yarn.tags";
    private static final String[] HADOOP_SITE_FILES = new String[]
            {"core-site.xml", "hdfs-site.xml", "mapred-site.xml", "yarn-site.xml"};

    /**
     * Hadoop's {@code log4j.properties} found on the classpath, if readable and present.
     */
    private static final String HADOOP_LOG4J_LOCATION = "log4j.properties";

    /**
     * Default {@code log4j.properties}, if Hadoop's one is not present or readable.
     * <p>
     * Its contents are mostly from Hadoop's {@code default-log4j.properties}.
     */
    private static final String DEFAULT_LOG4J_LOCATION = "default-log4j.properties";

    protected Properties log4jProperties = new Properties();

    protected static void run(Class<? extends LauncherMain> klass, String[] args) throws Exception {
        LauncherMain main = klass.newInstance();
        main.setupLog4jProperties();
        main.run(args);
    }

    @VisibleForTesting
    protected void setupLog4jProperties() {
        if (tryLoadLog4jPropertiesFromResource(HADOOP_LOG4J_LOCATION)) {
            return;
        }

        tryLoadLog4jPropertiesFromResource(DEFAULT_LOG4J_LOCATION);
    }

    private boolean tryLoadLog4jPropertiesFromResource(final String log4jLocation) {
        System.out.println(String.format("INFO: loading log4j config file %s.", log4jLocation));
        final URL log4jUrl = Thread.currentThread().getContextClassLoader().getResource(log4jLocation);
        if (log4jUrl != null) {
            try (final InputStream log4jStream = log4jUrl.openStream()) {
                log4jProperties.load(log4jStream);

                System.out.println(String.format("INFO: log4j config file %s loaded successfully.", log4jLocation));
                return true;
            } catch (final IOException e) {
                System.out.println(
                        String.format("WARN: log4j config file %s is not readable. Exception message is: %s",
                                log4jLocation,
                                e.getMessage()));
                e.printStackTrace(System.out);
            }
        }
        else {
            System.out.println(String.format("WARN: log4j config file %s is not present.", log4jLocation));
        }

        System.out.println(String.format("INFO: log4j config file %s could not be loaded.", log4jLocation));
        return false;
    }

    protected static String getHadoopJobIds(String logFile, Pattern[] patterns) {
        Set<String> jobIds = new LinkedHashSet<>();
        if (!new File(logFile).exists()) {
            System.err.println("Log file: " + logFile + "  not present. Therefore no Hadoop job IDs found.");
        }
        else {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(logFile), "UTF-8"))) {
                String line = br.readLine();
                while (line != null) {
                    extractJobIDs(line, patterns, jobIds);
                    line = br.readLine();
                }
            } catch (IOException e) {
                System.out.println("WARN: Error getting Hadoop Job IDs. logFile: " + logFile);
                e.printStackTrace(System.out);
            }
        }
        return jobIds.isEmpty() ? null : StringUtils.join(jobIds, ",");
    }

    @VisibleForTesting
    protected static void extractJobIDs(String line, Pattern[] patterns, Set<String> jobIds) {
        Preconditions.checkNotNull(line);
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(line);
            if (matcher.find()) {
                String jobId = matcher.group(1);
                if (StringUtils.isEmpty(jobId) || jobId.equalsIgnoreCase("NULL")) {
                    continue;
                }
                jobId = jobId.replaceAll("application", "job");
                jobIds.add(jobId);
            }
        }
    }

    protected static void writeExternalChildIDs(String logFile, Pattern[] patterns, String name) {
        // Harvesting and recording Hadoop Job IDs
        String jobIds = getHadoopJobIds(logFile, patterns);
        if (jobIds != null) {
            File externalChildIdsFile = new File(System.getProperty(EXTERNAL_CHILD_IDS));
            try (OutputStream externalChildIdsStream = new FileOutputStream(externalChildIdsFile)) {
                externalChildIdsStream.write(jobIds.getBytes("UTF-8"));
                System.out.println("Hadoop Job IDs executed by " + name + ": " + jobIds);
                System.out.println();
            } catch (IOException e) {
                System.out.println("WARN: Error while writing to external child ids file: " +
                        System.getProperty(EXTERNAL_CHILD_IDS));
                e.printStackTrace(System.out);
            }
        } else {
            System.out.println("No child hadoop job is executed.");
        }
    }

    public static Set<ApplicationId> getChildYarnJobs(Configuration actionConf) {
        return getChildYarnJobs(actionConf, ApplicationsRequestScope.OWN);
    }

    public static Set<ApplicationId> getChildYarnJobs(Configuration actionConf, ApplicationsRequestScope scope,
                                                      long startTime) {
        Set<ApplicationId> childYarnJobs = new HashSet<>();
        String tag = actionConf.get(CHILD_MAPREDUCE_JOB_TAGS);
        if (tag == null) {
            System.out.print("Could not find Yarn tags property " + CHILD_MAPREDUCE_JOB_TAGS);
            return childYarnJobs;
        }
        System.out.println("tag id : " + tag);
        GetApplicationsRequest gar = GetApplicationsRequest.newInstance();
        gar.setScope(scope);
        gar.setApplicationTags(Collections.singleton(tag));
        long endTime = System.currentTimeMillis();
        if (startTime > endTime) {
            System.out.println("WARNING: Clock skew between the Oozie server host and this host detected.  Please fix this.  " +
                    "Attempting to work around...");
            // We don't know which one is wrong (relative to the RM), so to be safe, let's assume they're both wrong and add an
            // offset in both directions
            long diff = 2 * (startTime - endTime);
            startTime = startTime - diff;
            endTime = endTime + diff;
        }
        gar.setStartRange(startTime, endTime);
        try {
            ApplicationClientProtocol proxy = ClientRMProxy.createRMProxy(actionConf, ApplicationClientProtocol.class);
            GetApplicationsResponse apps = proxy.getApplications(gar);
            List<ApplicationReport> appsList = apps.getApplicationList();
            for(ApplicationReport appReport : appsList) {
                childYarnJobs.add(appReport.getApplicationId());
            }
        } catch (YarnException | IOException ioe) {
            throw new RuntimeException("Exception occurred while finding child jobs", ioe);
        }

        System.out.println("Child yarn jobs are found - " + StringUtils.join(childYarnJobs, ","));
        return childYarnJobs;
    }
    public static Set<ApplicationId> getChildYarnJobs(Configuration actionConf, ApplicationsRequestScope scope) {
        System.out.println("Fetching child yarn jobs");

        long startTime = 0L;
        try {
            startTime = Long.parseLong(System.getProperty(OOZIE_JOB_LAUNCH_TIME));
        } catch(NumberFormatException nfe) {
            throw new RuntimeException("Could not find Oozie job launch time", nfe);
        }
        return getChildYarnJobs(actionConf, scope, startTime);
    }

    public static void killChildYarnJobs(Configuration actionConf) {
        try {
            Set<ApplicationId> childYarnJobs = getChildYarnJobs(actionConf);
            if (!childYarnJobs.isEmpty()) {
                System.out.println();
                System.out.println("Found [" + childYarnJobs.size() + "] Map-Reduce jobs from this launcher");
                System.out.println("Killing existing jobs and starting over:");
                YarnClient yarnClient = YarnClient.createYarnClient();
                yarnClient.init(actionConf);
                yarnClient.start();
                for (ApplicationId app : childYarnJobs) {
                    System.out.print("Killing job [" + app + "] ... ");
                    yarnClient.killApplication(app);
                    System.out.println("Done");
                }
                System.out.println();
            }
        } catch (IOException | YarnException ye) {
            throw new RuntimeException("Exception occurred while killing child job(s)", ye);
        }
    }

    protected abstract void run(String[] args) throws Exception;

    /**
     * Write to STDOUT (the task log) the Configuration/Properties values. All properties that contain
     * any of the strings in the maskSet will be masked when writting it to STDOUT.
     *
     * @param header message for the beginning of the Configuration/Properties dump.
     * @param maskSet set with substrings of property names to mask.
     * @param conf Configuration/Properties object to dump to STDOUT
     * @throws IOException thrown if an IO error ocurred.
     */

    protected static void logMasking(String header, Collection<String> maskSet, Iterable<Map.Entry<String,String>> conf)
            throws IOException {
        StringWriter writer = new StringWriter();
        writer.write(header + "\n");
        writer.write("--------------------\n");
        for (Map.Entry<String, String> entry : conf) {
            String name = (String) entry.getKey();
            String value = (String) entry.getValue();
            for (String mask : maskSet) {
                if (name.contains(mask)) {
                    value = "*MASKED*";
                }
            }
            writer.write(" " + name + " : " + value + "\n");
        }
        writer.write("--------------------\n");
        writer.close();
        System.out.println(writer.toString());
        System.out.flush();
    }

    /**
     * Get file path from the given environment
     */
    protected static String getFilePathFromEnv(String env) {
        String path = System.getenv(env);
        if (path != null && Shell.WINDOWS) {
            // In Windows, file paths are enclosed in \" so remove them here
            // to avoid path errors
            if (path.charAt(0) == '"') {
                path = path.substring(1);
            }
            if (path.charAt(path.length() - 1) == '"') {
                path = path.substring(0, path.length() - 1);
            }
        }
        return path;
    }

    /**
     * Read action configuration passes through action xml file.
     *
     * @return action  Configuration
     * @throws IOException
     */
    public static Configuration loadActionConf() throws IOException {
        // loading action conf prepared by Oozie
        Configuration actionConf = new Configuration(false);

        String actionXml = System.getProperty("oozie.action.conf.xml");

        if (actionXml == null) {
            throw new RuntimeException("Missing Java System Property [oozie.action.conf.xml]");
        }
        if (!new File(actionXml).exists()) {
            throw new RuntimeException("Action Configuration XML file [" + actionXml + "] does not exist");
        }

        actionConf.addResource(new Path("file:///", actionXml));
        return actionConf;
    }

    protected static void setYarnTag(Configuration actionConf) {
        if(actionConf.get(CHILD_MAPREDUCE_JOB_TAGS) != null) {
            // in case the user set their own tags, appending the launcher tag.
            if(actionConf.get(MAPREDUCE_JOB_TAGS) != null) {
                actionConf.set(MAPREDUCE_JOB_TAGS, actionConf.get(MAPREDUCE_JOB_TAGS) + ","
                        + actionConf.get(CHILD_MAPREDUCE_JOB_TAGS));
            } else {
                actionConf.set(MAPREDUCE_JOB_TAGS, actionConf.get(CHILD_MAPREDUCE_JOB_TAGS));
            }
        }
    }

    protected static void setApplicationTags(Configuration configName, String tagConfigName) {
        if (configName.get(MAPREDUCE_JOB_TAGS) != null) {
            System.out.println("Setting [" + tagConfigName + "] tag: " + configName.get(MAPREDUCE_JOB_TAGS));
            configName.set(tagConfigName, configName.get(MAPREDUCE_JOB_TAGS));
        }
    }

    /**
     * Utility method that copies the contents of the src file into all of the dst file(s).
     * It only requires reading the src file once.
     *
     * @param src The source file
     * @param dst The destination file(s)
     * @throws IOException
     */
    protected static void copyFileMultiplex(File src, File... dst) throws IOException {
        InputStream is = null;
        OutputStream[] osa = new OutputStream[dst.length];
        try {
            is = new FileInputStream(src);
            for (int i = 0; i < osa.length; i++) {
                osa[i] = new FileOutputStream(dst[i]);
            }
            byte[] buffer = new byte[4096];
            int read;
            while ((read = is.read(buffer)) > -1) {
                for (OutputStream os : osa) {
                    os.write(buffer, 0, read);
                }
            }
        } finally {
            if (is != null) {
                is.close();
            }
            for (OutputStream os : osa) {
                if (os != null) {
                    os.close();
                }
            }
        }
    }

    protected void writeHadoopConfig(String actionXml, File basrDir) throws IOException {
        File actionXmlFile = new File(actionXml);
        System.out.println("Copying " + actionXml + " to " + basrDir + "/" + Arrays.toString(HADOOP_SITE_FILES));
        boolean directoryCreated = basrDir.mkdirs();

        if (directoryCreated) {
            File[] dstFiles = new File[HADOOP_SITE_FILES.length];

            for (int i = 0; i < dstFiles.length; i++) {
                dstFiles[i] = new File(basrDir, HADOOP_SITE_FILES[i]);
            }

            copyFileMultiplex(actionXmlFile, dstFiles);
        }
    }

    /**
     * Print arguments to standard output stream. Mask out argument values to option with name 'password' in them.
     * @param banner source banner
     * @param args arguments to be printed
     */
    void printArgs(String banner, String[] args) {
        System.out.println(banner);
        boolean maskNextArg = false;
        for (String arg : args) {
            if (maskNextArg) {
                System.out.println("             " + "********");
                maskNextArg = false;
            }
            else {
                System.out.println("             " + arg);
                if (arg.toLowerCase(Locale.getDefault()).contains("password")) {
                    maskNextArg = true;
                }
            }
        }
    }
}

class LauncherMainException extends Exception {
    private int errorCode;

    public LauncherMainException(int code) {
        errorCode = code;
    }

    int getErrorCode() {
        return errorCode;
    }
}
