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
[ ] Frontend: - vs ?, no check for fks
[ ] 2 - pass architecture for preparing types before transformation
[ ] 3 jobs: type-methos, functions, triggers
[ ] 1 more job for package body data
[ ] 1 more job for oracle build ins
[ ] evaluate usage of s/get_config for package variable replacement!
[ ] function stubs need ast parsing too!
Let's proceed with starting the implementation of the actual function-code-transformation. I usually want you to proceed in small
steps that allow for manual testing in between. So I have thought we could create a mechasims for manual review, similar as we did
with @src/main/resources/META-INF/resources/sqltester.html ... What files we would need to create to get this basically working for
standalone functions (the main Visitor could at first just return "todo" or something like that)? Which brings us to the next
question: what is actually the real grammar element that will fit the