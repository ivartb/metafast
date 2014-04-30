metafast
========

Fast metagenome analysis toolkit.

Print available tools:
~~~ sh
java -jar metafast.jar -ts
~~~

### Authors - TODO

### Examples - TODO

~~~ sh
java -Xmx14G -jar metafast.jar -t kmer-counter -k 31 -i SRR413558merged.fastq -v --force -w tmpWD
java -Xmx15G -jar metafast.jar -t sequences-builder -bp 10 -k 31 -l 100 --reads SRR413558merged.binq --force -v --max-size 2000000000
~~~

### See also

* [khmer](https://github.com/ged-lab/khmer) - toolkit to split your reads
* [crAss](http://edwards.sdsu.edu/crass/) - Cross-Assembly of Metagenomes
* [MaryGold](http://sourceforge.net/projects/metavar/) - Variation analysis of metagenomic samples
