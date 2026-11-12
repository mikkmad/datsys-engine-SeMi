grammar Sql;

options { caseInsensitive = true; }   // ANTLR ≥ 4.10

script      : (statement ';')+ EOF ;
statement   : createTable | copy | select | delete ;

createTable : CREATE TABLE IDENTIFIER '(' columnDef (',' columnDef)* ')' ;
columnDef   : IDENTIFIER columnType ;
columnType  : STRING | LONG | DOUBLE ;

copy        : COPY IDENTIFIER FROM STRING_LITERAL ;

select      : SELECT ('*' | columnList) FROM IDENTIFIER (',' IDENTIFIER)* (WHERE condition)? ;
delete      : DELETE FROM IDENTIFIER (WHERE filter)? ;
columnList  : IDENTIFIER (',' IDENTIFIER)* ;
condition   : predicate (AND predicate)* ;     // conjunction only: no OR, no parentheses
predicate   : filter | join ;
filter      : IDENTIFIER comparison=('=' | '<' | '>') literal ;
join        : left=IDENTIFIER '=' right=IDENTIFIER ;
literal     : STRING_LITERAL | DOUBLE_LITERAL | LONG_LITERAL ;

// Lexer. Keyword rules MUST precede IDENTIFIER, or IDENTIFIER swallows them.
CREATE : 'CREATE' ;   TABLE : 'TABLE' ;   COPY : 'COPY' ;   FROM : 'FROM' ;
SELECT : 'SELECT' ;   DELETE : 'DELETE' ;   WHERE : 'WHERE' ;   AND : 'AND' ;
STRING : 'STRING' ;   LONG : 'LONG' ;   DOUBLE : 'DOUBLE' ;

IDENTIFIER      : [A-Z_] [A-Z_0-9]* ;        // caseInsensitive covers a–z
DOUBLE_LITERAL  : '-'? [0-9]+ '.' [0-9]+ ;
LONG_LITERAL    : '-'? [0-9]+ ;
STRING_LITERAL  : '\'' ~['\r\n]* '\'' ;
LINE_COMMENT    : '--' ~[\r\n]* -> skip ;
WS              : [ \t\r\n]+ -> skip ;

