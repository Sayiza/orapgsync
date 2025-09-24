# Oracle to PostgreSQL Synchronization and Migration Tool

A Oracle-to-PostgreSQL Synchronization Migration system 
There are three phases:
 - tables
 - data
 - state else, plsql related

The source db is oracle, the target is postgres
Target can by synced on the fly, in updated in whole or in part
There are no scripts, state is in memory

Current state: table and data: some fragements are present, plsql currently nothing and to be done later
The currently present code are remnants from a previous project that 

Current focus: 
-Try to get connections to oracle and postgres to work, and confirm that on the frontend: the frontend goes into the resources folder: vanilla js only
-Make a configuration mechanism that starts with the properties in application.properties but can be overridden from the frontend, config classes go into the folder config
-Read in the tables that are present in the source db and display theme in the frontend
-Create the tables in the postgre db is not present
-To make that work for more custom datatype do the reading on the objectypes but variables only

# Start PostgreSQL test container
docker rm -f pgtest; docker run --name pgtest -e POSTGRES_PASSWORD=secret -p 5432:5432 -d postgres
