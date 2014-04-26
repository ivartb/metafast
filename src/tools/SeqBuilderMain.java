package tools;

import algo.HashMapOperations;
import algo.SequencesFinders;
import io.IOUtils;
import it.unimi.dsi.fastutil.longs.Long2IntMap.Entry;
import ru.ifmo.genetics.dna.DnaTools;
import ru.ifmo.genetics.dna.kmers.KmerIteratorFactory;
import ru.ifmo.genetics.dna.kmers.ShortKmer;
import ru.ifmo.genetics.dna.kmers.ShortKmerIteratorFactory;
import ru.ifmo.genetics.structures.map.ArrayLong2IntHashMap;
import ru.ifmo.genetics.utils.Misc;
import ru.ifmo.genetics.utils.NumUtils;
import ru.ifmo.genetics.utils.tool.ExecutionFailedException;
import ru.ifmo.genetics.utils.tool.Parameter;
import ru.ifmo.genetics.utils.tool.Tool;
import ru.ifmo.genetics.utils.tool.inputParameterBuilder.*;

import java.io.*;
import java.util.ArrayList;
import java.util.Collections;

public class SeqBuilderMain extends Tool {
    public static final String NAME = "sequences-builder";
    public static final String DESCRIPTION = "Metagenome De Bruijn graph analysis and sequences building";

    static final int LOAD_TASK_SIZE = 1 << 15;

    static final int STAT_LEN = 1024;

    static final String SEQUENCES_FILENAME = "sequences.fasta";
    static final String DISTIBUTION_FILENAME = "distribution";

    public final Parameter<Integer> maximalBadFrequency = addParameter(new IntParameterBuilder("maximal-bad-frequence")
            .optional()
            .withShortOpt("b")
            .withDescription("maximal frequency for a kmer to be assumed erroneous")
            .create());

    public final Parameter<Integer> bottomCutPercent = addParameter(new IntParameterBuilder("bottom-cut-percent")
            .optional()
            .withShortOpt("bp")
            .withDescription("k-mers percent to be assumed erroneous")
            .create());

    public final Parameter<Integer> k = addParameter(new IntParameterBuilder("k")
            .mandatory()
            .withShortOpt("k")
            .withDescription("k-mer size")
            .create());

    public final Parameter<Integer> sequenceLen = addParameter(new IntParameterBuilder("sequence-len")
            .mandatory()
            .withShortOpt("l")
            .withDescription("sequence minimal length to be written to " + SEQUENCES_FILENAME)
            .create());

    public final Parameter<Long> maxSize = addParameter(new LongParameterBuilder("max-size")
            .optional()
            .withDescription("maximal hashset size")
            .withDefaultValue(NumUtils.highestBits(Misc.availableMemory() / 42, 3))
            .memoryParameter()
            .create());

    public final Parameter<File[]> inputFiles = addParameter(new FileMVParameterBuilder("reads")
            .withShortOpt("i")
            .mandatory()
            .withDescription("list of input files")
            .create());

    public final Parameter<KmerIteratorFactory> kmerIteratorFactory = Parameter.createParameter(
            new KmerIteratorFactoryParameterBuilder("kmer-iterator-factory")
                    .optional()
                    .withDescription("factory used for iterating through kmers")
                    .withDefaultValue(new ShortKmerIteratorFactory())
                    .create());

    private int LEN;
    private long MAX_SIZE;

    @Override
    protected void runImpl() throws ExecutionFailedException {
        LEN = k.get();
        MAX_SIZE = maxSize.get();

        if (maximalBadFrequency.get() != null && bottomCutPercent.get() != null) {
            throw new IllegalArgumentException("-b and -bp can not be set both");
        }

        debug("MAXIMAL_SIZE = " + MAX_SIZE);

        ArrayLong2IntHashMap hm;
        try {
            hm = IOUtils.loadBINQReads(inputFiles.get(), LEN, LOAD_TASK_SIZE,
                    kmerIteratorFactory.get(), availableProcessors.get(), this.logger);
        } catch (IOException e) {
            throw new ExecutionFailedException("Couldn't load kmers", e);
        }

        long totalKmers = 0;
        int[] stat = new int[STAT_LEN];
        for (int i = 0; i < hm.hm.length; ++i) {
            for (int value : hm.hm[i].values()) {
                totalKmers += value;
                if (value >= stat.length) {
                    value = stat.length - 1;
                }
                ++stat[value];
            }
        }

        try {
            dumpStat(stat, workDir + File.separator + DISTIBUTION_FILENAME);
        } catch (FileNotFoundException e) {
            throw new ExecutionFailedException(e);
        }

        if (bottomCutPercent.get() != null) {
            long kmersToCut = totalKmers * bottomCutPercent.get() / 100;
            debug("K-mers under given threshold = " + kmersToCut);
            long currentKmersCount = 0;
            for (int i = 0; i < stat.length - 1; i++) {
                if (currentKmersCount >= kmersToCut) {
                    maximalBadFrequency.set(i);
                    break;
                }
                currentKmersCount += (long) i * stat[i];
            }
        }

        if (maximalBadFrequency.get() == null) {
            int threshold = 1;
            long currentSum = 0;
            while (stat[threshold] * (long) threshold > stat[threshold + 1] * (long) (threshold + 1)) {
                currentSum += stat[threshold];
                if (currentSum * 2 > totalKmers) {
                    debug("Threshold search stopped at 50 %");
                    break;
                }
                threshold++;
            }
            maximalBadFrequency.set(threshold);
        }
        info("Maximal bad frequency = " + maximalBadFrequency.get());

//        try {
//            calcSequences(hm, workDir + File.separator + SEQUENCES_FILENAME);
//        } catch (FileNotFoundException e) {
//            e.printStackTrace();
//        }
        info("hm brackets = " + hm.hm.length);
        info("Sequences found = " + SequencesFinders.thresholdStrategy(hm, maximalBadFrequency.get(), sequenceLen.get(), k.get(), this.logger).size());

    }

    public void calcSequences(ArrayLong2IntHashMap hm, String fastaFP) throws FileNotFoundException {
        int freqThreshold = maximalBadFrequency.get();
        int lenThreshold = sequenceLen.get();
        int kValue = k.get();

        HashMapOperations.banBranchingKmers(hm, freqThreshold, kValue, this.logger);

        int sequenceId = 0;

        ArrayList<Integer> sequenceLen = new ArrayList<Integer>();
        ArrayList<Long> sequenceWeight = new ArrayList<Long>();

        long kmersInSeq = 0;
        long totalKmersInSequences = 0;

        PrintWriter fastaPW = new PrintWriter(fastaFP);

        for (int i = 0; i < hm.hm.length; ++i) {
            for (Entry entry : hm.hm[i].long2IntEntrySet()) {
                int value = entry.getIntValue();
                if (value <= freqThreshold) {
                    continue;
                }
                long key = entry.getLongKey();
                ShortKmer kmer = new ShortKmer(key, kValue);

                if (HashMapOperations.getLeftNucleotide(hm, kmer, freqThreshold) >= 0) {
                    continue;
                }

                StringBuilder sequenceSB = new StringBuilder(kmer.toString());
                long seqWeight = 0, minWeight = value, maxWeight = value;

                while (true) {
                    long kmerRepr = kmer.toLong();
                    value = hm.get(kmerRepr);
                    seqWeight += value;
                    minWeight = Math.min(minWeight, value);
                    maxWeight = Math.max(maxWeight, value);

                    hm.add(kmerRepr, -(value + 1));

                    byte rightNuc = HashMapOperations.getRightNucleotide(hm, kmer, freqThreshold);
                    if (rightNuc < 0) {
                        break;
                    }
                    sequenceSB.append(DnaTools.toChar(rightNuc));
                    kmer.shiftRight(rightNuc);
                }

                if (sequenceSB.length() >= lenThreshold) {
                    sequenceId++;
                    String sequenceStr = sequenceSB.toString();

                    sequenceLen.add(sequenceStr.length());
                    sequenceWeight.add(seqWeight);

                    totalKmersInSequences += seqWeight;
                    kmersInSeq += sequenceStr.length() - kValue + 1;

                    String seqInfo = String.format(">%d length=%d sum_weight=%d min_weight=%d max_weight=%d",
                            sequenceId, sequenceStr.length(), seqWeight, minWeight, maxWeight);
                    fastaPW.println(seqInfo);
                    fastaPW.println(sequenceStr);

                    if (sequenceId % 10000 == 0) {
                        debug("sequenceId = " + sequenceId + ", last len = " + sequenceStr.length());
                    }
                }

            }
        }
        info(sequenceId + " sequences found");
        info(kmersInSeq + " unique k-mers out of " + hm.size() + " in sequences");
        info("Total k-mers in sequences = " + totalKmersInSequences);
        info("N50 value of sequences = " + getN50(sequenceLen));

        dumpSeqInfo(sequenceLen, sequenceWeight, workDir + File.separator + "seq-info");

        fastaPW.close();
    }

    void dumpStat(int[] stat, String filename) throws FileNotFoundException {
        PrintWriter pw = new PrintWriter(filename);
        for (int i = 1; i < stat.length; ++i) {
            pw.println(i + " " + stat[i]);
        }
        pw.close();
    }

    void dumpSeqInfo(ArrayList<Integer> lens, ArrayList<Long> weights, String filename) throws FileNotFoundException {
        PrintWriter pw = new PrintWriter(filename);
        for (int i = 1; i < lens.size(); ++i) {
            pw.println(lens.get(i) + " " + weights.get(i));
        }
        pw.close();
    }

    int getN50(ArrayList<Integer> lens) {
        ArrayList<Integer> sorted = new ArrayList<Integer>(lens);
        Collections.sort(sorted);
        long sum = 0;
        for (int x : sorted) {
            sum += x;
        }
        long topSum = 0;
        for (int i = sorted.size() - 1; i >= 0; i--) {
            topSum += sorted.get(i);
            if (topSum * 2 >= sum) {
                return sorted.get(i);
            }
        }
        return -1;
    }

    @Override
    protected void cleanImpl() {
    }

    public static void main(String[] args) {
        new SeqBuilderMain().mainImpl(args);
    }

    public SeqBuilderMain() {
        super(NAME, DESCRIPTION);
    }
}
