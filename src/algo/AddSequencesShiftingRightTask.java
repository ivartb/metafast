package algo;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import ru.ifmo.genetics.dna.DnaTools;
import ru.ifmo.genetics.dna.kmers.ShortKmer;
import ru.ifmo.genetics.structures.map.ArrayLong2IntHashMap;
import ru.ifmo.genetics.utils.KmerUtils;
import structures.Sequence;

import java.util.List;
import java.util.Queue;

/**
 * Task class to search and add simple sequences to queue <code>sequences</code>.
 */
public class AddSequencesShiftingRightTask implements Runnable {

    final ArrayLong2IntHashMap hm;
    final Long2IntOpenHashMap openHM;
    final int k;
    int freqThreshold;
    int lenThreshold;
    final Queue<Sequence> sequences;
    final LongOpenHashSet used;

    public AddSequencesShiftingRightTask(ArrayLong2IntHashMap hm,
                                         Long2IntOpenHashMap openHM,
                                         int k, int freqThreshold, int lenThreshold,
                                         Queue<Sequence> sequences, LongOpenHashSet used) {
        this.hm = hm;
        this.openHM = openHM;
        this.k = k;
        this.freqThreshold = freqThreshold;
        this.lenThreshold = lenThreshold;
        this.sequences = sequences;
        this.used = used;
    }

    @Override
    public void run() {
        for (Long2IntMap.Entry entry : openHM.long2IntEntrySet()) {
            int value = entry.getIntValue();
            if (value <= freqThreshold) {
                continue;
            }

            long key = entry.getLongKey();
            ShortKmer kmerF = new ShortKmer(key, k);
            ShortKmer[] kmers = new ShortKmer[]{kmerF, kmerF.rc()};

            for (ShortKmer kmer : kmers) {
                boolean isLeft = false;
                byte nuc = HashMapOperations.getLeftNucleotide(hm, kmer, freqThreshold);
                if (nuc < 0) {
                    isLeft = true;
                } else {
                    byte rightNuc = kmer.nucAt(k - 1);
                    kmer.shiftLeft(nuc);
                    if (HashMapOperations.getRightNucleotide(hm, kmer, freqThreshold) < 0) {
                        isLeft = true;
                    }
                    kmer.shiftRight(rightNuc);
                }

                if (isLeft) {
                    processSequence(kmer);
                }
            }
        }
    }

    private void processSequence(ShortKmer startKmer) {
        int value = hm.get(startKmer.toLong());

        StringBuilder sequenceSB = new StringBuilder(startKmer.toString());
        long seqWeight = value;
        int minWeight = value, maxWeight = value;

        ShortKmer kmer = new ShortKmer(startKmer);

        while (true) {
            byte rightNuc = HashMapOperations.getRightNucleotide(hm, kmer, freqThreshold);
            if (rightNuc < 0) {
                break;
            }
            kmer.shiftRight(rightNuc);
            byte leftNuc = HashMapOperations.getLeftNucleotide(hm, kmer, freqThreshold);
            if (leftNuc < 0) {
                break;
            }

            sequenceSB.append(DnaTools.toChar(rightNuc));
            value = hm.get(kmer.toLong());
            seqWeight += value;
            minWeight = Math.min(minWeight, value);
            maxWeight = Math.max(maxWeight, value);
        }

        if (sequenceSB.length() >= lenThreshold) {
            long stKmer = startKmer.toLong();
            long endKmer = kmer.toLong();

            // we want to print one sequence of two (fw and rc) - one with min long value of start kmer
            if (stKmer > endKmer) {
                return;
            }

            if (stKmer == endKmer) {  // print any sequence, but only one of them
                synchronized (used) {
                    if (used.contains(stKmer) || used.contains(endKmer)) {
                        // sequence was already printed
                        return;
                    } else {
                        used.add(stKmer);
                    }
                }
            }
            sequences.add(new Sequence(sequenceSB.toString(),
                    (int) (seqWeight / (sequenceSB.length() - k + 1)), minWeight, maxWeight));
        }
    }
}
