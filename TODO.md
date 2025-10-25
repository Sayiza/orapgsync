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
[ ] Frontend: - vs ?, no check for fks
[ ] 2 - pass architecture for preparing types before transformation
[ ] 3 jobs: type-methos, functions, triggers
[ ] 1 more job for package body data
[ ] 1 more job for oracle build ins
[ ] evaluate usage of s/get_config for package variable replacement!
I want to prepare the next open step in @TRANSFORMATION.md (nr 25), even though the sql step is not fully completed, it is already very
usefull... I like a frontend driven approach, so let's start by adding a new row to the main table in
@src/main/resources/META-INF/resources/index.html and following the established pattern. Compare it with the conceptually very
similar "view implementation step" (24) : We will be dealing with standalone functions and procedure only in this step.