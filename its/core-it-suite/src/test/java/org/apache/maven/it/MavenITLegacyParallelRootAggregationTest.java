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
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenITLegacyParallelRootAggregationTest extends AbstractMavenIntegrationTestCase {
    @Test
    void aggregatesAfterBothChildrenProduceOutput(@TempDir Path reactor) throws Exception {
        Verifier verifier = prepareReactor(reactor);
        verifier.addCliArgument("package");
        verifier.execute();
        verifier.verifyErrorFreeLog();

        assertEquals("a=true b=true aa=false", Files.readString(reactor.resolve("target/aggregate-observed.txt")));
        assertTrue(Files.isRegularFile(reactor.resolve("target/aggregate.jar")));
    }

    @Test
    void skipsAggregationWhenAChildFailsAfterProducingOutput(@TempDir Path reactor) throws Exception {
        Verifier verifier = prepareReactor(reactor);
        verifier.addCliArguments("--fail-at-end", "-DverificationFailure=a", "verify");
        assertThrows(VerificationException.class, verifier::execute);
        verifier.verifyTextInLog("Intentional failure in a");
        verifier.verifyTextInLog("(fail-after-output)");

        assertEquals("a", Files.readString(reactor.resolve("a/target/touched.txt")));
        assertEquals("b", Files.readString(reactor.resolve("b/target/touched.txt")));
        assertTrue(Files.isRegularFile(reactor.resolve("target/touched.txt")), "Root cleanup must still run");
        assertFalse(Files.exists(reactor.resolve("target/aggregate-observed.txt")));
        assertFalse(Files.exists(reactor.resolve("target/aggregate.jar")));
    }

    private Verifier prepareReactor(Path reactor) throws Exception {
        Path plugin = extractResources("mng-13763").resolve("plugin");
        Verifier installer = newVerifier(plugin);
        installer.addCliArgument("install");
        installer.execute();
        installer.verifyErrorFreeLog();

        Path fixture = extractResources("sequential-root-aggregation");
        for (String relative : List.of("pom.xml", "a/pom.xml", "b/pom.xml")) {
            Path destination = reactor.resolve(relative);
            Files.createDirectories(destination.getParent());
            Files.copy(fixture.resolve(relative), destination);
        }

        Verifier verifier = newVerifier(reactor);
        verifier.addCliArguments("--builder", "multithreaded", "-T2");
        return verifier;
    }
}
