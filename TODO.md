[x] Make datatype get all variables
[x] XML Type
[x] aq types
[x] synonym frontend
[x] synonym extraction job and state saving
[x] synonym resolving for types
[x] clear up usage of reflections
[x] sequences before tables, because default can be a seq
[x] special column names : "#", "end", "offset"
[x] Failed to serialize LOB type LONG 
[x] Unmapped defaul values: strip comments, and brackets
[x] SQL Transform: subquery support in all phases, outer join support in all phases
[x] revisit outer join transformer once all subquery support is in place
[x] The AND handler (lines 17-30) correctly handles null (filtered ROWNUM), but OR doesn't. If ROWNUM appears in an OR condition, this will cause "null OR something".
[x] PL/SQL Variable declarations visitor (Step 25 Phase 2.1)
[x] PL/SQL Assignment statements visitor (Step 25 Phase 2.1)
[x] PL/SQL IF/ELSIF/ELSE statements visitor (Step 25 Phase 2.2)
[x] PL/SQL SELECT INTO statements visitor (Step 25 Phase 2.3)
[x] function stubs need ast parsing too! or not
[x] Frontend: - vs ?, no check for fks
[x] 2 - pass architecture for preparing types before transformation
[ ] 3 jobs: type-methos, functions, triggers
[x] 1 more job for package body data - no, integrated now
[x] 1 more job for oracle build ins
[x] evaluate usage of s/get_config for package variable replacement!
[x] insert update delete migration, basics are ok
[ ] complex types to json - sync with package needs
[x] test failures - compare to plan inline
[ ] data transfer step: CLOB size 24586928 characters exceeds limit 20971520 in column response, skipping (will insert NULL)
[ ] Unknown complex type anydata for column ... meldung anderes log level
[ ] Stubs for type members are very bad
[ ] type member jobs
[ ] trigger jobs, and integreation test
[ ] broken views: co_loc_bew.la_master_v, co_slc_xm_legacy.dp_ex_exams_v, co_slc_xm_legacy.dp_xm_candidates_v, co_loc_bwst.bwst_v_ant
[x] co_gpr memory
[x] full job run 2025-11-10: 8 hour total duration, 8% of functions implemented, 30% of views implemented, most views with functions do not work. Datatransfer 99% working 10GB in 1 hour 
[ ] sort all results
[ ] make verify result get code only on request
[ ] TO_NUMBER(TO_CHAR( not working
[ ] full run with tr*nk*dev and NO errors, final todo ;-)
