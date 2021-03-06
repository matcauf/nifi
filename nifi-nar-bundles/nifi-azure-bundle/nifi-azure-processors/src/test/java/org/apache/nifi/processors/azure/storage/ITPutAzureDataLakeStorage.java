/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.processors.azure.storage;

import com.azure.storage.file.datalake.DataLakeDirectoryClient;
import com.azure.storage.file.datalake.DataLakeFileClient;
import com.google.common.collect.Sets;
import com.google.common.net.UrlEscapers;
import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.processor.Processor;
import org.apache.nifi.provenance.ProvenanceEventRecord;
import org.apache.nifi.provenance.ProvenanceEventType;
import org.apache.nifi.util.MockFlowFile;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ITPutAzureDataLakeStorage extends AbstractAzureDataLakeStorageIT {

    private static final String DIRECTORY = "dir1";
    private static final String FILE_NAME = "file1";
    private static final byte[] FILE_DATA = "0123456789".getBytes();

    private static final String EL_FILESYSTEM = "az.filesystem";
    private static final String EL_DIRECTORY = "az.directory";
    private static final String EL_FILE_NAME = "az.filename";

    @Override
    protected Class<? extends Processor> getProcessorClass() {
        return PutAzureDataLakeStorage.class;
    }

    @Before
    public void setUp() {
        runner.setProperty(PutAzureDataLakeStorage.DIRECTORY, DIRECTORY);
        runner.setProperty(PutAzureDataLakeStorage.FILE, FILE_NAME);
    }

    @Test
    public void testPutFileToExistingDirectory() throws Exception {
        fileSystemClient.createDirectory(DIRECTORY);

        runProcessor(FILE_DATA);

        assertSuccess(DIRECTORY, FILE_NAME, FILE_DATA);
    }

    @Test
    public void testPutFileToNonExistingDirectory() throws Exception {
        runProcessor(FILE_DATA);

        assertSuccess(DIRECTORY, FILE_NAME, FILE_DATA);
    }

    @Test
    public void testPutFileToDeepDirectory() throws Exception {
        String baseDirectory = "dir1/dir2";
        String fullDirectory = baseDirectory + "/dir3/dir4";
        fileSystemClient.createDirectory(baseDirectory);
        runner.setProperty(PutAzureDataLakeStorage.DIRECTORY, fullDirectory);

        runProcessor(FILE_DATA);

        assertSuccess(fullDirectory, FILE_NAME, FILE_DATA);
    }

    @Test
    public void testPutFileToRootDirectory() throws Exception {
        String rootDirectory = "";
        runner.setProperty(PutAzureDataLakeStorage.DIRECTORY, rootDirectory);

        runProcessor(FILE_DATA);

        assertSuccess(rootDirectory, FILE_NAME, FILE_DATA);
    }

    @Test
    public void testPutEmptyFile() throws Exception {
        byte[] fileData = new byte[0];

        runProcessor(fileData);

        assertSuccess(DIRECTORY, FILE_NAME, fileData);
    }

    @Ignore
    // ignore excessive test with larger file size
    @Test
    public void testPutBigFile() throws Exception {
        byte[] fileData = new byte[100_000_000];

        runProcessor(fileData);

        assertSuccess(DIRECTORY, FILE_NAME, fileData);
    }

    @Test
    public void testPutFileWithNonExistingFileSystem() {
        runner.setProperty(PutAzureDataLakeStorage.FILESYSTEM, "dummy");

        runProcessor(FILE_DATA);

        assertFailure();
    }

    @Test
    public void testPutFileWithInvalidDirectory() {
        runner.setProperty(PutAzureDataLakeStorage.DIRECTORY, "/dir1");

        runProcessor(FILE_DATA);

        assertFailure();
    }

    @Test
    public void testPutFileWithInvalidFileName() {
        runner.setProperty(PutAzureDataLakeStorage.FILE, "/file1");

        runProcessor(FILE_DATA);

        assertFailure();
    }

    @Test
    public void testPutFileWithSpacesInDirectoryAndFileName() throws Exception {
        String directory = "dir 1";
        String fileName = "file 1";
        runner.setProperty(PutAzureDataLakeStorage.DIRECTORY, directory);
        runner.setProperty(PutAzureDataLakeStorage.FILE, fileName);

        runProcessor(FILE_DATA);

        assertSuccess(directory, fileName, FILE_DATA);
    }

    @Ignore
    // the existing file gets overwritten without error
    // seems to be a bug in the Azure lib
    @Test
    public void testPutFileToExistingFile() {
        fileSystemClient.createFile(String.format("%s/%s", DIRECTORY, FILE_NAME));

        runProcessor(FILE_DATA);

        assertFailure();
    }

    @Test
    public void testPutFileWithEL() throws Exception {
        Map<String, String> attributes = createAttributesMap();
        setELProperties();

        runProcessor(FILE_DATA, attributes);

        assertSuccess(DIRECTORY, FILE_NAME, FILE_DATA);
    }

    @Test
    public void testPutFileWithELButFilesystemIsNotSpecified() {
        Map<String, String> attributes = createAttributesMap();
        attributes.remove(EL_FILESYSTEM);
        setELProperties();

        runProcessor(FILE_DATA, attributes);

        assertFailure();
    }

    @Test
    public void testPutFileWithELButFileNameIsNotSpecified() {
        Map<String, String> attributes = createAttributesMap();
        attributes.remove(EL_FILE_NAME);
        setELProperties();

        runProcessor(FILE_DATA, attributes);

        assertFailure();
    }

    private Map<String, String> createAttributesMap() {
        Map<String, String> attributes = new HashMap<>();

        attributes.put(EL_FILESYSTEM, fileSystemName);
        attributes.put(EL_DIRECTORY, DIRECTORY);
        attributes.put(EL_FILE_NAME, FILE_NAME);

        return attributes;
    }

    private void setELProperties() {
        runner.setProperty(PutAzureDataLakeStorage.FILESYSTEM, String.format("${%s}", EL_FILESYSTEM));
        runner.setProperty(PutAzureDataLakeStorage.DIRECTORY, String.format("${%s}", EL_DIRECTORY));
        runner.setProperty(PutAzureDataLakeStorage.FILE, String.format("${%s}", EL_FILE_NAME));
    }

    private void runProcessor(byte[] fileData) {
        runProcessor(fileData, Collections.emptyMap());
    }

    private void runProcessor(byte[] testData, Map<String, String> attributes) {
        runner.assertValid();
        runner.enqueue(testData, attributes);
        runner.run();
    }

    private void assertSuccess(String directory, String fileName, byte[] fileData) throws Exception {
        assertFlowFile(directory, fileName, fileData);
        assertAzureFile(directory, fileName, fileData);
        assertProvenanceEvents();
    }

    private void assertFlowFile(String directory, String fileName, byte[] fileData) throws Exception {
        runner.assertAllFlowFilesTransferred(PutAzureDataLakeStorage.REL_SUCCESS, 1);

        MockFlowFile flowFile = runner.getFlowFilesForRelationship(PutAzureDataLakeStorage.REL_SUCCESS).get(0);

        flowFile.assertContentEquals(fileData);

        flowFile.assertAttributeEquals("azure.filesystem", fileSystemName);
        flowFile.assertAttributeEquals("azure.directory", directory);
        flowFile.assertAttributeEquals("azure.filename", fileName);

        String urlEscapedDirectory = UrlEscapers.urlPathSegmentEscaper().escape(directory);
        String urlEscapedFileName = UrlEscapers.urlPathSegmentEscaper().escape(fileName);
        String primaryUri = StringUtils.isNotEmpty(directory)
                ? String.format("https://%s.dfs.core.windows.net/%s/%s/%s", getAccountName(), fileSystemName, urlEscapedDirectory, urlEscapedFileName)
                : String.format("https://%s.dfs.core.windows.net/%s/%s", getAccountName(), fileSystemName, urlEscapedFileName);
        flowFile.assertAttributeEquals("azure.primaryUri", primaryUri);

        flowFile.assertAttributeEquals("azure.length", Integer.toString(fileData.length));
    }

    private void assertAzureFile(String directory, String fileName, byte[] fileData) {
        DataLakeFileClient fileClient;
        if (StringUtils.isNotEmpty(directory)) {
            DataLakeDirectoryClient directoryClient = fileSystemClient.getDirectoryClient(directory);
            assertTrue(directoryClient.exists());

            fileClient = directoryClient.getFileClient(fileName);
        } else {
            fileClient = fileSystemClient.getFileClient(fileName);
        }

        assertTrue(fileClient.exists());
        assertEquals(fileData.length, fileClient.getProperties().getFileSize());
    }

    private void assertProvenanceEvents() {
        Set<ProvenanceEventType> expectedEventTypes = Sets.newHashSet(ProvenanceEventType.SEND);

        Set<ProvenanceEventType> actualEventTypes = runner.getProvenanceEvents().stream()
                .map(ProvenanceEventRecord::getEventType)
                .collect(Collectors.toSet());
        assertEquals(expectedEventTypes, actualEventTypes);
    }

    private void assertFailure() {
        runner.assertAllFlowFilesTransferred(PutAzureDataLakeStorage.REL_FAILURE, 1);
    }
}
