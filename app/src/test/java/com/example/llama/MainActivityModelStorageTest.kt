package com.example.llama

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class MainActivityModelStorageTest {
    @Test
    fun getStoredModelFiles_returnsSortedGgufFilesOnly() {
        val modelsDir = createTempDir("models")
        File(modelsDir, "zeta.gguf").writeText("z")
        File(modelsDir, "notes.txt").writeText("x")
        File(modelsDir, "alpha.gguf").writeText("a")
        File(modelsDir, "beta.bin").writeText("b")

        val result = MainActivityModelStorage.getStoredModelFiles(modelsDir)

        assertEquals(listOf("alpha.gguf", "zeta.gguf"), result.map { it.name })
    }
}
