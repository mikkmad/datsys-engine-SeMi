package datasys.semi;

public record ScanStats(int partitionsTotal, int partitionsRead, int partitionsPruned) {
}