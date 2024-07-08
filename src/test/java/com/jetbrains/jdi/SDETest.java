package com.jetbrains.jdi;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SDETest {
    private static final String A_CLASS = toOSSpecificPath("kt/breakpoints/inline/A");
    private static final String B_CLASS = toOSSpecificPath("kt/breakpoints/inline/B");

    // Original code:
    //
    //object A {
    //    @JvmStatic
    //    inline fun inlineFun() {
    //        println() // line = 6
    //    }
    //}
    //
    //class B {
    //    fun inlineInlineFun() {
    //        A.inlineFun()
    //    }
    //}
    // // total lines in file = 26
    private static final String TEST_SMAP = """
            SMAP
            inline.kt
            Kotlin
            *S Kotlin
            *F
            + 1 inline.kt
            """ +
            B_CLASS + '\n' + """
            + 2 inline.kt
            """ +
            A_CLASS + '\n' + """
            *L
            1#1,26:1
            6#2,2:27
            *S KotlinDebug
            *F
            + 1 inline.kt
            """ +
            B_CLASS + '\n' + """
            *L
            12#1:27,2
            *E""";

    private SDE getTestSde() {
        SDE sde = new SDE(TEST_SMAP);
        assertTrue(sde.isValid());
        return sde;
    }

    @Test
    void testStratumOrNull() {
        SDE sde = getTestSde();
        SDE.Stratum kotlinStratum = sde.stratum("Kotlin");
        assertNotNull(kotlinStratum);
        assertNotNull(sde.stratum("KotlinDebug"));
        // Java stratum is always present
        assertNotNull(sde.stratum("Java"));

        SDE.Stratum nonExistentStratum = sde.stratum("Non-existent stratum");
        // Always returns the default stratum if nothing found
        assertEquals("Kotlin", nonExistentStratum.id());
    }

    @Test
    void testSourcePaths() {
        SDE sde = getTestSde();
        SDE.Stratum stratum = sde.stratum("Kotlin");

        // we can pass null here as a source path is present in the SMAP
        List<String> sourcePaths = stratum.sourcePaths(null);

        assertEquals(List.of(B_CLASS, A_CLASS), sourcePaths);
    }

    @Test
    void testContainsLine() {
        SDE sde = getTestSde();
        SDE.Stratum stratum = sde.stratum("Kotlin");

        for (int i = 0; i < 5; i++) {
            assertFalse(stratum.hasMappedLineTo(null, A_CLASS, i));
        }
        assertTrue(stratum.hasMappedLineTo(null, A_CLASS, 6));
        assertTrue(stratum.hasMappedLineTo(null, A_CLASS, 7));
        for (int i = 8; i < 27; i++) {
            assertFalse(stratum.hasMappedLineTo(null, A_CLASS, i));
        }
    }

    @Test
    void testAvailableStrata() {
        SDE sde = getTestSde();
        assertEquals(List.of("Java", "Kotlin", "KotlinDebug"), sde.availableStrata());
    }

    @Test
    void testGetLine() {
        SDE sde = getTestSde();
        assertNull(sde.getLine("Kotlin", 0));
        for (int i = 1; i <= 26; i++) {
            assertEquals(new SDE.LineAndSourcePath(i, B_CLASS), sde.getLine("Kotlin", i));
        }

        assertEquals(new SDE.LineAndSourcePath(6, A_CLASS), sde.getLine("Kotlin", 27));
        assertEquals(new SDE.LineAndSourcePath(7, A_CLASS), sde.getLine("Kotlin", 28));
        assertNull(sde.getLine("Kotlin", 29));
    }

    private static String toOSSpecificPath(String unixPath) {
        return unixPath.replace("/", File.separator);
    }
}
