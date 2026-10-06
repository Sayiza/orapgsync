package me.christianrobert.orapgsync.core.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserExcluderTest {

  @Test
  void excludesOracleSuppliedSchemasCaseInsensitively() {
    assertTrue(UserExcluder.is2BeExclueded("SYS"));
    assertTrue(UserExcluder.is2BeExclueded("mdsys"));
    assertTrue(UserExcluder.is2BeExclueded("Gsmadmin_Internal"));
  }

  @Test
  void excludesSchemasPreinstalledOnNewerReleases() {
    assertTrue(UserExcluder.is2BeExclueded("XS$NULL"));
    assertTrue(UserExcluder.is2BeExclueded("SYS$UMF"));
    assertTrue(UserExcluder.is2BeExclueded("VECSYS"));
    assertTrue(UserExcluder.is2BeExclueded("PDBADMIN"));
  }

  @Test
  void matchesWholeNamesOnly() {
    assertFalse(UserExcluder.is2BeExclueded("SYSADM"));
    assertFalse(UserExcluder.is2BeExclueded("MY_SYS"));
  }

  @Test
  void noLongerExcludesFormerPrefixPatterns() {
    assertFalse(UserExcluder.is2BeExclueded("XSALES"));
    assertFalse(UserExcluder.is2BeExclueded("DEV_UTIL"));
  }

  @Test
  void keepsApplicationSchemas() {
    assertFalse(UserExcluder.is2BeExclueded("HR"));
  }
}
