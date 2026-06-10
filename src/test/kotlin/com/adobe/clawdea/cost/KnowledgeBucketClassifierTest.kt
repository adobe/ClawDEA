package com.adobe.clawdea.cost

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KnowledgeBucketClassifierTest {
    @Test fun `seed-wiki is wiki create`() {
        assertEquals(KnowledgeBucket.WIKI_CREATE, KnowledgeBucketClassifier.classify("/seed-wiki"))
    }
    @Test fun `refresh-wiki is wiki update`() {
        assertEquals(KnowledgeBucket.WIKI_UPDATE, KnowledgeBucketClassifier.classify("/refresh-wiki --apply"))
    }
    @Test fun `wiki-author digest is wiki update`() {
        assertEquals(KnowledgeBucket.WIKI_UPDATE, KnowledgeBucketClassifier.classify("@wiki-author Acting on these drift events."))
    }
    @Test fun `seed-workspace is workspace create`() {
        assertEquals(KnowledgeBucket.WORKSPACE_CREATE, KnowledgeBucketClassifier.classify("/seed-workspace"))
    }
    @Test fun `normal prompt is null`() {
        assertNull(KnowledgeBucketClassifier.classify("fix the bug in Foo.kt"))
        assertNull(KnowledgeBucketClassifier.classify(""))
    }
    @Test fun `classifyCommand maps slash-command names`() {
        // Commands dispatch an expanded template, so attribution classifies by command name.
        assertEquals(KnowledgeBucket.WIKI_CREATE, KnowledgeBucketClassifier.classifyCommand("/seed-wiki"))
        assertEquals(KnowledgeBucket.WIKI_UPDATE, KnowledgeBucketClassifier.classifyCommand("/refresh-wiki"))
        assertEquals(KnowledgeBucket.WORKSPACE_CREATE, KnowledgeBucketClassifier.classifyCommand("/seed-workspace"))
        assertNull(KnowledgeBucketClassifier.classifyCommand("/clear"))
    }
}
