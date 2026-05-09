/*
 * Copyright 2026 Adobe. All rights reserved.
 * This file is licensed to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License. You may obtain a copy
 * of the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS
 * OF ANY KIND, either express or implied. See the License for the specific language
 * governing permissions and limitations under the License.
 */
package com.adobe.clawdea.knowledge.workspace.seed

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class CandidateFingerprinterTest {
    private fun write(parent: Path, rel: String, content: String) {
        val target = parent.resolve(rel); Files.createDirectories(target.parent); Files.writeString(target, content)
    }
    @Test fun `derives kebab key from dir name`() {
        val tmp = Files.createTempDirectory("fp")
        val d = Files.createDirectory(tmp.resolve("My_Project.Repo"))
        assertEquals("my-project-repo", CandidateFingerprinter.fingerprint(d, gitLog = emptyList()).key)
    }
    @Test fun `extracts package roots from src-main-java`() {
        val tmp = Files.createTempDirectory("fp")
        val d = Files.createDirectory(tmp.resolve("proj"))
        write(d, "src/main/java/com/example/foo/Foo.java", "package com.example.foo;")
        write(d, "src/main/kotlin/org/sample/Bar.kt", "package org.sample")
        assertEquals(setOf("com.example", "org.sample"),
            CandidateFingerprinter.fingerprint(d, emptyList()).packageRoots)
    }
    @Test fun `extracts pom artifactId and direct deps`() {
        val tmp = Files.createTempDirectory("fp")
        val d = Files.createDirectory(tmp.resolve("proj"))
        write(d, "pom.xml", "<project><artifactId>my-artifact</artifactId><dependencies>" +
            "<dependency><groupId>com.x</groupId><artifactId>a</artifactId></dependency>" +
            "<dependency><groupId>com.y</groupId><artifactId>b</artifactId></dependency>" +
            "</dependencies></project>")
        val fp = CandidateFingerprinter.fingerprint(d, emptyList())
        assertEquals("my-artifact", fp.pomArtifactId)
        assertEquals(setOf("com.x:a", "com.y:b"), fp.pomDeps)
    }
    @Test fun `histograms JIRA prefixes from git log subjects`() {
        val tmp = Files.createTempDirectory("fp")
        val d = Files.createDirectory(tmp.resolve("proj"))
        val log = listOf("feat(SITES-1234): foo", "fix(CQ-99): bar", "SITES-2: hi", "chore: nothing")
        assertEquals(mapOf("SITES" to 2, "CQ" to 1),
            CandidateFingerprinter.fingerprint(d, gitLog = log).jiraPrefixes)
    }
    @Test fun `parses git remote org from a github URL`() {
        assertEquals("github.com/Example-Org",
            CandidateFingerprinter.parseRemoteOrg("git@github.com:Example-Org/ClawDEA.git"))
        assertEquals("git.example.com/platform",
            CandidateFingerprinter.parseRemoteOrg("https://git.example.com/platform/frontend.git"))
    }
    @Test fun `fingerprint exposes pomArtifactIds pomGroupIds and javaImports fields`() {
        val tmp = Files.createTempDirectory("fp")
        val d = Files.createDirectory(tmp.resolve("proj"))
        val fp = CandidateFingerprinter.fingerprint(d, gitLog = emptyList())
        // Just assert the fields exist with sensible defaults — actual content tested per-extractor in later tasks
        assertEquals(emptySet<String>(), fp.pomArtifactIds)
        assertEquals(emptySet<String>(), fp.pomGroupIds)
        assertEquals(emptySet<String>(), fp.javaImports)
    }
    @Test fun `extractPomArtifactIds collects every artifactId across root and submodule poms`() {
        val tmp = Files.createTempDirectory("fp")
        val d = Files.createDirectory(tmp.resolve("multi-mod"))
        write(d, "pom.xml", "<project><artifactId>parent-aggregator</artifactId></project>")
        write(d, "core/pom.xml", "<project><artifactId>core-impl</artifactId></project>")
        write(d, "api/pom.xml", "<project><artifactId>api</artifactId></project>")
        val fp = CandidateFingerprinter.fingerprint(d, gitLog = emptyList())
        assertEquals(setOf("parent-aggregator", "core-impl", "api"), fp.pomArtifactIds)
    }
    @Test fun `extractPomGroupIds collects every groupId across root and submodule poms`() {
        val tmp = Files.createTempDirectory("fp")
        val d = Files.createDirectory(tmp.resolve("runtime-fork"))
        write(d, "pom.xml", "<project><groupId>com.example.runtime</groupId><artifactId>runtime-parent</artifactId></project>")
        write(d, "engine/pom.xml", "<project><groupId>com.example.runtime</groupId><artifactId>runtime-engine</artifactId></project>")
        write(d, "api/pom.xml", "<project><groupId>com.example.runtime.api</groupId><artifactId>runtime-api</artifactId></project>")
        val fp = CandidateFingerprinter.fingerprint(d, gitLog = emptyList())
        assertEquals(setOf("com.example.runtime", "com.example.runtime.api"), fp.pomGroupIds)
    }
    @Test fun `extractJavaImports keeps top-2 segments and drops java and kotlin stdlib`() {
        val tmp = Files.createTempDirectory("fp")
        val d = Files.createDirectory(tmp.resolve("proj"))
        write(d, "src/main/java/com/foo/Foo.java", """
            package com.foo;
            import java.util.List;
            import java.io.File;
            import org.apache.runtime.api.resource.Resource;
            import com.example.vault.Session;
            import org.apache.runtime.api.RuntimeHttpServletRequest;
            public class Foo {}
        """.trimIndent())
        write(d, "src/main/kotlin/org/sample/Bar.kt", """
            package org.sample
            import kotlin.collections.List
            import org.junit.jupiter.api.Test
            import com.acme.platform.foo.Bar
        """.trimIndent())
        val fp = CandidateFingerprinter.fingerprint(d, gitLog = emptyList())
        // Top-2 segments only; java.* and kotlin.* dropped; com.example kept; org.junit kept; com.acme kept; org.apache kept once
        assertEquals(setOf("org.apache", "com.example", "org.junit", "com.acme"), fp.javaImports)
    }

    @Test fun `extractJavaImports handles import static`() {
        val tmp = Files.createTempDirectory("fp")
        val d = Files.createDirectory(tmp.resolve("proj"))
        write(d, "src/main/java/com/example/Foo.java", """
            package com.example;
            import static org.junit.jupiter.api.Assertions.assertEquals;
            public class Foo {}
        """.trimIndent())
        val fp = CandidateFingerprinter.fingerprint(d, gitLog = emptyList())
        assertEquals(setOf("org.junit"), fp.javaImports)
    }

    @Test fun `extractPomFingerprint excludes artifactIds and groupIds inside dependency blocks`() {
        val tmp = Files.createTempDirectory("fp")
        val d = Files.createDirectory(tmp.resolve("proj"))
        write(d, "pom.xml", """
            <project>
                <groupId>com.example</groupId>
                <artifactId>my-artifact</artifactId>
                <dependencies>
                    <dependency>
                        <groupId>org.slf4j</groupId>
                        <artifactId>slf4j-api</artifactId>
                    </dependency>
                    <dependency>
                        <groupId>junit</groupId>
                        <artifactId>junit</artifactId>
                    </dependency>
                </dependencies>
            </project>
        """.trimIndent())
        val fp = CandidateFingerprinter.fingerprint(d, gitLog = emptyList())
        assertEquals(setOf("my-artifact"), fp.pomArtifactIds)
        assertEquals(setOf("com.example"), fp.pomGroupIds)
    }

    @Test fun `extractPomFingerprint includes parent groupId and artifactId but not dependencies`() {
        val tmp = Files.createTempDirectory("fp")
        val d = Files.createDirectory(tmp.resolve("proj"))
        write(d, "pom.xml", """
            <project>
                <parent>
                    <groupId>org.sonatype.oss</groupId>
                    <artifactId>oss-parent</artifactId>
                </parent>
                <artifactId>child-artifact</artifactId>
                <dependencies>
                    <dependency>
                        <groupId>biz.aQute.bnd</groupId>
                        <artifactId>bndlib</artifactId>
                    </dependency>
                </dependencies>
            </project>
        """.trimIndent())
        val fp = CandidateFingerprinter.fingerprint(d, gitLog = emptyList())
        // Parent coords AND own artifactId — but NOT bndlib/biz.aQute.bnd
        assertEquals(setOf("oss-parent", "child-artifact"), fp.pomArtifactIds)
        assertEquals(setOf("org.sonatype.oss"), fp.pomGroupIds)
    }

    @Test fun `extractPomFingerprint excludes dependencyManagement and plugin coords`() {
        val tmp = Files.createTempDirectory("fp")
        val d = Files.createDirectory(tmp.resolve("proj"))
        write(d, "pom.xml", """
            <project>
                <groupId>com.example</groupId>
                <artifactId>my-art</artifactId>
                <dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>org.junit</groupId>
                            <artifactId>junit-bom</artifactId>
                        </dependency>
                    </dependencies>
                </dependencyManagement>
                <build>
                    <plugins>
                        <plugin>
                            <groupId>org.apache.maven.plugins</groupId>
                            <artifactId>maven-surefire-plugin</artifactId>
                        </plugin>
                    </plugins>
                </build>
            </project>
        """.trimIndent())
        val fp = CandidateFingerprinter.fingerprint(d, gitLog = emptyList())
        assertEquals(setOf("my-art"), fp.pomArtifactIds)
        assertEquals(setOf("com.example"), fp.pomGroupIds)
    }
}
