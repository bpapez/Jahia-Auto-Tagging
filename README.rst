Jahia Auto Tagging
==================

This is a custom automatic tagging module for the Jahia xCM platform that 
enables integrating automatic tagging of content and documents through rules (or
in future with user interaction).

Licensing
---------
This module is free software; you can redistribute it and/or 
modify it under the terms of the GNU General Public License 
as published by the Free Software Foundation; either version 2 
of the License, or (at your option) any later version

Disclaimer
----------
This module was developed by Benjamin Papez and is distributed in the hope that
it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty
of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.

The status of this development is a "Prototype" and is not targeted to be deployed
and run on a production instance of Jahia xCM.

Requirements
------------
Module is targeted to be deployed to Jahia xCM version 6.6 (or later).

Description
-----------
There are different approaches to use synonym queries. One can either use query time or index time
synonym expansion. Jahia with this module supports both. 

- Query time expansion

When deciding to use query time expansion, then synonym search will be done
only for terms which start with ~ (tilde). Either the users need to know this syntax or one could
make a search template, which automatically adds the tilde if the user clicks on a provided 
checkbox to also look for synonyms. Query time synonym expansion is limited to single terms and 
does not work with phrases.

- Index time expansion

With index time expansion all the synonyms are expanded already during indexing time, so synonyms
will always be active on search and there is no need to explicitly set the tilde. The disadvantage
of index time synonym expansion is that whenever you change the synonym dictionary, you need to recreate
the whole index. The advantage iof index time expansion is that you can also use synonyms for phrases and
also that the relevancy calculation will be more correct than with using query time expansion (see 
explanation in `Solr <http://wiki.apache.org/solr/AnalyzersTokenizersTokenFilters#solr.SynonymFilterFactory>`_ ).

For both approaches you can decide whether you want to use a custom built synonym dictionary or
whether you want to use an available synonym dictionary, like for instance the 
`WordNet synonym dictionary <http://wordnet.princeton.edu/>`_.  The custom build dictionary is
enough if you want to just provide domain-specific synonyms like abbreviations or product names. 

Usage
-----
Query time expansion with a properties file
```````````````````````````````````````````
Jahia xCM version 6.5 already provides a synonym search capability out of the box (you do not 
need to install this module for it). It simply uses a Java properties file in which you can 
configure custom synonyms. 

To activate it you need to configure the following files::

  ..\WEB-INF\etc\repository\jackrabbit\repository.xml
  ..\WEB-INF\var\repository\workspaces\default\workspace.xml
  ..\WEB-INF\var\repository\workspaces\live\workspace.xml

You need to add the following setting to the Workspace->SearchIndex element::

  <param name="synonymProviderClass" value="org.apache.jackrabbit.core.query.lucene.PropertiesSynonymProvider"/>
  <param name="synonymProviderConfigPath" value="synonyms.properties"/>

The path interpreted relative to the index directory, which on default is::

  ..\WEB-INF\var\repository\workspaces\default\index\
  ..\WEB-INF\var\repository\workspaces\live\index\

The syntax of the properties file is simply::

  word1=word2
  word3=word1
  word2=word4

In the above case word1 will have as synonyms word2 and word3, whereas word2 will have as synonyms
word1 and word4. The limitation of this syntax is, that the same word can not appear twice on the left
hand side of the equation as only the last will be taken.

Query time expansion with a WordNet dictionary
``````````````````````````````````````````````
In order to use the existing English `WordNet synonym dictionary <http://wordnet.princeton.edu/>`_, you can deploy 
this custom module to Jahia and then in the same files as mentioned above configure the Workspace->SearchIndex element 
and set::    

  <param name="synonymProviderClass" value="org.apache.jackrabbit.core.query.wordnet.WordNetSynonyms"/>
  
This way whenever the user prefixes a term with the tilde (e.g. ~construction) it will also search for words with
a similar meaning.    

Index time expansion with a Solr SynonymFilterFactory
`````````````````````````````````````````````````````
Jahia already uses Apache Solr to offer faceting support, so we also provide with this module an integration of 
the Solr based SynonymFilter, which is used to expand words with their synonyms during indexing time.

To use it you first need to deploy this custom module and configure the same files as mentioned above, just that
now you need to modify the exisiting "analyzer" parameter in the element Workspace->SearchIndex->::
 
  <param name="analyzer" value="org.jahia.services.search.analyzer.SynonymAnalyzer"/> 

After deploying the module, there will be a synonyms.txt file in the WEB-INF\lib\synonymSearch-*.jar. This file 
has some example synonyms for testing. You can create your own synonyms.txt file and place it under WEB-INF\classes.

Notice that whenever you make changes in the synonyms.txt dictionary, you will manually trigger a reindexing of
your site, by shutting the server down, deleting all indexes under::

  ..\WEB-INF\var\repository\workspaces\default\index\
  ..\WEB-INF\var\repository\workspaces\live\index\
  
and then again start the server, which will automatically re-create the indexes on startup.

The syntax of the synonyms.txt file is explained in this `Solr Wiki article <http://wiki.apache.org/solr/AnalyzersTokenizersTokenFilters#solr.SynonymFilterFactory>`_

The synonym, ignoreCase, expand and tokenizerFactory parameters can be set in the file::

  ..\modules\synonymSearch\resources\JahiaSynonymSearch.properties
  
With index time synonym expansion you do not need to use the tilde during query time, as synonyms will be already automatically searched.   