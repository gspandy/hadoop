/* Generated By:JavaCC: Do not edit this line. ParserConstants.java */
/**
 * Copyright 2007 The Apache Software Foundation
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.shell.generated;

public interface ParserConstants {

  int EOF = 0;
  int HELP = 5;
  int CLEAR = 6;
  int SHOW = 7;
  int DESCRIBE = 8;
  int CREATE = 9;
  int DROP = 10;
  int EXIT = 11;
  int INSERT = 12;
  int DELETE = 13;
  int SELECT = 14;
  int ROW = 15;
  int COLUMN = 16;
  int TIME = 17;
  int VALUES = 18;
  int COLUMNFAMILIES = 19;
  int WHERE = 20;
  int LIMIT = 21;
  int AND = 22;
  int OR = 23;
  int COMMA = 24;
  int DOT = 25;
  int LPAREN = 26;
  int RPAREN = 27;
  int EQUALS = 28;
  int NOTEQUAL = 29;
  int OPTIONS = 30;
  int ID = 31;
  int NUM = 32;
  int STRING = 33;
  int QUOTED_STRING = 34;
  int STRING_LITERAL = 35;

  int DEFAULT = 0;

  String[] tokenImage = {
    "<EOF>",
    "\" \"",
    "\"\\t\"",
    "\"\\r\"",
    "\"\\n\"",
    "\"help\"",
    "\"clear\"",
    "\"show\"",
    "\"describe\"",
    "\"create\"",
    "\"drop\"",
    "\"exit\"",
    "\"insert\"",
    "\"delete\"",
    "\"select\"",
    "\"row\"",
    "\"column\"",
    "\"time\"",
    "\"values\"",
    "\"columnfamilies\"",
    "\"where\"",
    "\"limit\"",
    "\"and\"",
    "\"or\"",
    "\",\"",
    "\".\"",
    "\"(\"",
    "\")\"",
    "\"=\"",
    "\"<>\"",
    "\"-\"",
    "<ID>",
    "<NUM>",
    "<STRING>",
    "<QUOTED_STRING>",
    "<STRING_LITERAL>",
    "\";\"",
  };

}
