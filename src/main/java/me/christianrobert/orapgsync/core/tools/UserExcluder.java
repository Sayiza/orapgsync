package me.christianrobert.orapgsync.core.tools;

import java.util.Locale;
import java.util.Set;

public class UserExcluder {

  /**
   * Oracle-supplied schemas that are never migrated, matched case-insensitively. Schema extraction
   * additionally filters on ALL_USERS.ORACLE_MAINTAINED (12c+); this list is what every other
   * name check relies on, and also covers schemas Oracle does not flag (PDBADMIN, SQLNAV).
   */
  private static final Set<String> EXCLUDED_USERS = Set.of(
      "ANONYMOUS",
      "APPQOSSYS",
      "AUDSYS",
      "BAASSYS",
      "CTXSYS",
      "DBSFWUSER",
      "DBSNMP",
      "DGPDB_INT",
      "DIP",
      "DVF",
      "DVSYS",
      "GGSHAREDCAP",
      "GGSYS",
      "GSMADMIN_INTERNAL",
      "GSMCATUSER",
      "GSMROOTUSER",
      "GSMUSER",
      "LBACSYS",
      "MDDATA",
      "MDSYS",
      "OJVMSYS",
      "OLAPSYS",
      "ORDDATA",
      "ORDPLUGINS",
      "ORDSYS",
      "OUTLN",
      "PDBADMIN",
      "REMOTE_SCHEDULER_AGENT",
      "SI_INFORMTN_SCHEMA",
      "SQLNAV",
      "SYS",
      "SYS$UMF",
      "SYSBACKUP",
      "SYSDG",
      "SYSKM",
      "SYSRAC",
      "SYSTEM",
      "VECSYS",
      "WMSYS",
      "XDB",
      "XS$NULL"
  );

  public static boolean is2BeExclueded(String user) {
    return EXCLUDED_USERS.contains(user.toUpperCase(Locale.ROOT));
  }
}
