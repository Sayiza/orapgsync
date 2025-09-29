package me.christianrobert.orapgsync.core.tools;

public class UserExcluder {
  public static boolean is2BeExclueded(String user) {
    return is2BeExclueded(user, null);
  }

  public static boolean is2BeExclueded(String user, String useCase) {
    if (user.matches("(?i)SYS|SYSTEM|OUTLN|SQLNAV|XDB|WMSYS|DEV_UT.*|ORDDATA|CTXSYS|XS.*|DBSNMP|OLAPSYS"))
      return true;
    if (user.matches("^(SYS|SYSTEM|XDB|ORDDATA|ORDSYS|OUTLN|MDSYS|CTXSYS|DBSNMP|GSMADMIN_INTERNAL|WMSYS|APPQOSSYS|AUDSYS|OJVMSYS|LBACSYS|OLAPSYS|SI_INFORMTN_SCHEMA)$"))
      return true;
    if (useCase != null && useCase.startsWith("TYPE")) {
      if (user.matches("CO_RES_CORE")) {
        return true;
      }
    }
    return false;
  }
}
