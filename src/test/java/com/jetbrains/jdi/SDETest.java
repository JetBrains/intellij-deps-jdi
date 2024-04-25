package com.jetbrains.jdi;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SDETest {
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
            kt/breakpoints/inline/B
            + 2 inline.kt
            kt/breakpoints/inline/A
            *L
            1#1,26:1
            6#2,2:27
            *S KotlinDebug
            *F
            + 1 inline.kt
            kt/breakpoints/inline/B
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

        assertEquals(List.of("kt/breakpoints/inline/B", "kt/breakpoints/inline/A"), sourcePaths);
    }

    @Test
    void testContainsLine() {
        SDE sde = getTestSde();
        SDE.Stratum stratum = sde.stratum("Kotlin");

        for (int i = 0; i < 5; i++) {
            assertFalse(stratum.hasMappedLineTo(null, "kt/breakpoints/inline/A", i));
        }
        assertTrue(stratum.hasMappedLineTo(null, "kt/breakpoints/inline/A", 6));
        assertTrue(stratum.hasMappedLineTo(null, "kt/breakpoints/inline/A", 7));
        for (int i = 8; i < 27; i++) {
            assertFalse(stratum.hasMappedLineTo(null, "kt/breakpoints/inline/A", i));
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
            assertEquals(new SDE.LineAndSourcePath(i, "kt/breakpoints/inline/B"), sde.getLine("Kotlin", i));
        }

        assertEquals(new SDE.LineAndSourcePath(6, "kt/breakpoints/inline/A"), sde.getLine("Kotlin", 27));
        assertEquals(new SDE.LineAndSourcePath(7, "kt/breakpoints/inline/A"), sde.getLine("Kotlin", 28));
        assertNull(sde.getLine("Kotlin", 29));
    }
}
