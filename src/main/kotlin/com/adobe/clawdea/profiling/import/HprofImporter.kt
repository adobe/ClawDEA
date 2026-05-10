package com.adobe.clawdea.profiling.`import`

import com.adobe.clawdea.profiling.extensions.HprofExtensionInstaller
import com.adobe.clawdea.profiling.model.*
import java.net.URLClassLoader
import java.nio.file.Path

object HprofImporter {

    fun isAvailable(): Boolean = HprofExtensionInstaller.isInstalled()

    fun import(path: Path): Recording {
        val jar = HprofExtensionInstaller.jarPath()
            ?: error("Heap-dump support not installed. Install via Settings -> ClawDEA -> Profiling -> Extensions.")

        val classLoader = URLClassLoader(arrayOf(jar.toUri().toURL()), this::class.java.classLoader)
        val heapObjects = parseWithShark(path, classLoader)

        return Recording(
            source = Source.IMPORTED_HPROF,
            timeRange = TimeRange(0L, 0L),
            cpuSamples = emptyList(),
            allocations = emptyList(),
            heap = heapObjects,
            meta = mapOf("hprof.path" to path.toString()),
        )
    }

    @Suppress("UNUSED_PARAMETER")
    private fun parseWithShark(path: Path, classLoader: ClassLoader): List<HeapObject> {
        // Placeholder — will be implemented when shark jar is available for testing.
        // The bridge will call:
        //   shark.HprofHeapGraph.Companion.openHeapGraph(path.toFile())
        //   then iterate instances, building HeapObject list
        return emptyList()
    }
}
