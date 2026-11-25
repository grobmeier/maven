/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.maven.it;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenITSequentialRootAggregationTest extends AbstractMavenIntegrationTestCase {
    @Test
    void aggregatesAfterSuccessfulChildVerification() throws Exception {
        Path reactor = prepareReactor();
        Verifier verifier = sequentialBuild(reactor);
        verifier.execute();
        verifier.verifyErrorFreeLog();

        assertEquals("a", Files.readString(reactor.resolve("a/target/touched.txt")));
        assertEquals("b", Files.readString(reactor.resolve("b/target/touched.txt")));
        assertEquals("a=true b=true aa=false", Files.readString(reactor.resolve("target/aggregate-observed.txt")));
        assertTrue(Files.isRegularFile(reactor.resolve("target/aggregate.jar")));
        assertFalse(Files.exists(reactor.resolve("a/target/aggregate-observed.txt")));
        assertFalse(Files.exists(reactor.resolve("b/target/aggregate-observed.txt")));
    }

    @Test
    void skipsAggregationWhenChildVerificationFailsAfterOutput() throws Exception {
        Path reactor = prepareReactor();
        Verifier verifier = sequentialBuild(reactor);
        verifier.addCliArgument("-DverificationFailure=a");
        assertThrows(VerificationException.class, verifier::execute);
        verifier.verifyTextInLog("Intentional failure in a");
        verifier.verifyTextInLog("(fail-after-output)");

        // Package output must exist: an early root or dependency failure is not this regression.
        assertEquals("a", Files.readString(reactor.resolve("a/target/touched.txt")));
        assertFalse(Files.exists(reactor.resolve("target/aggregate-observed.txt")));
        assertFalse(Files.exists(reactor.resolve("target/aggregate.jar")));
        assertFalse(Files.exists(reactor.resolve("b/target/touched.txt")), "Fail-fast must skip child b");
    }

    private Path prepareReactor() throws Exception {
        // Reuse the existing aggregator probe without depending on the external acceptance lab.
        Path plugin = extractResources("mng-13763").resolve("plugin");
        Verifier installer = newVerifier(plugin);
        installer.deleteDirectory("target");
        installer.addCliArgument("install");
        installer.execute();
        installer.verifyErrorFreeLog();

        Path reactor = extractResources("sequential-root-aggregation");
        Verifier cleaner = newVerifier(reactor);
        for (String target : new String[] {"target", "a/target", "b/target"}) {
            cleaner.deleteDirectory(target);
        }
        return reactor;
    }

    private Verifier sequentialBuild(Path reactor) throws Exception {
        Verifier verifier = newVerifier(reactor);
        verifier.addCliArguments("--builder", "singlethreaded", "-T1", "--fail-fast", "verify");
        return verifier;
    }
}
