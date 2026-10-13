package app.arenawebview.locked.policy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * The configuration object is the one place where a mistake in the allow-list could pass unnoticed, so
 * its constructor has to refuse anything that would make the enforced policy differ from the readable
 * one.
 */
public class PolicyConfigTest {

    private static final AllowEntry ARENA = AllowEntry.anyPath("arena.ai", "test");
    private static final AllowEntry AUTH = AllowEntry.anyPath("auth.arena.ai", "test");

    @Test
    public void aHostListedTwiceIsAProgrammingErrorAndFailsAtConstruction() {
        // The second entry has a narrower path pin. Had the constructor accepted this, the first entry
        // would have won every lookup and the pin would have been dead text in the source.
        AllowEntry wide = AllowEntry.resourceOnly("www.gstatic.com", Collections.<String>emptyList(), "a");
        AllowEntry narrow = AllowEntry.resourceOnly("www.gstatic.com", Arrays.asList("/recaptcha/"), "b");
        try {
            new PolicyConfig("https://arena.ai/", Arrays.asList(ARENA, wide, narrow));
            fail("duplicate host must be refused");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("www.gstatic.com"));
        }
        // The same host object listed twice is just as wrong.
        try {
            new PolicyConfig("https://arena.ai/", Arrays.asList(ARENA, AUTH, ARENA));
            fail("duplicate host must be refused");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("arena.ai"));
        }
    }

    @Test
    public void nullEntriesAreRefused() {
        try {
            new PolicyConfig("https://arena.ai/", Arrays.asList(ARENA, null));
            fail("null entry must be refused");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void emptyConfigurationsAreRefused() {
        try {
            new PolicyConfig("https://arena.ai/", Collections.<AllowEntry>emptyList());
            fail("an empty allow-list must be refused");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            new PolicyConfig("", Arrays.asList(ARENA));
            fail("an empty primary URL must be refused");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            new PolicyConfig(null, Arrays.asList(ARENA));
            fail("a null primary URL must be refused");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void thePrimaryUrlMustPassItsOwnPolicy() {
        for (String bad : new String[]{"http://arena.ai/", "https://evil.example/", "https://arena.ai./",
                "https://auth.arena.ai@evil.example/", "about:blank"}) {
            try {
                new PolicyConfig(bad, Arrays.asList(ARENA, AUTH));
                fail("primary URL must be refused: " + bad);
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("primaryUrl"));
            }
        }
        // An asset-only host is not a page, so it cannot be the start page either.
        try {
            new PolicyConfig("https://www.gstatic.com/",
                    Arrays.asList(AllowEntry.resourceOnly("www.gstatic.com", null, "x")));
            fail("an asset host cannot be the start page");
        } catch (IllegalStateException expected) {
            // ok
        }
    }

    @Test
    public void lookupIsExactAndCaseSensitiveOnTheCanonicalForm() {
        PolicyConfig config = new PolicyConfig("https://arena.ai/", Arrays.asList(ARENA, AUTH));
        assertSame(ARENA, config.findByHost("arena.ai"));
        assertSame(AUTH, config.findByHost("auth.arena.ai"));
        // findByHost is only ever handed canonical hosts; anything else is simply absent.
        assertNull(config.findByHost("ARENA.AI"));
        assertNull(config.findByHost("arena.ai."));
        assertNull(config.findByHost("www.arena.ai"));
        assertNull(config.findByHost(""));
        assertNull(config.findByHost(null));
    }

    @Test
    public void entriesAreCopiedAndReadOnly() {
        List<AllowEntry> source = new java.util.ArrayList<AllowEntry>(Arrays.asList(ARENA, AUTH));
        PolicyConfig config = new PolicyConfig("https://arena.ai/", source);
        source.clear();
        assertEquals(2, config.entries().size());
        try {
            config.entries().add(ARENA);
            fail("entries must be read-only");
        } catch (UnsupportedOperationException expected) {
            // ok
        }
        assertEquals("arena.ai", config.entries().get(0).host());
        assertEquals("auth.arena.ai", config.entries().get(1).host());
    }

    @Test
    public void theProductionConfigurationHasNoDuplicateHostsAndNamesEveryGroup() {
        PolicyConfig production = ArenaAllowlist.productionConfig();
        assertNotNull(production);
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (AllowEntry entry : production.entries()) {
            assertTrue("host appears twice: " + entry.host(), seen.add(entry.host()));
            assertFalse("purpose must be recorded for " + entry.host(), entry.purpose().isEmpty());
        }
        assertEquals(ArenaAllowlist.PRIMARY_URL, production.primaryUrl());
        assertTrue(production.evaluateNavigation(production.primaryUrl()).isAllowed());
    }
}
