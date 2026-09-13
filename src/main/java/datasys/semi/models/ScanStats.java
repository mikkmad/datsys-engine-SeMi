package datasys.semi.models;

public record ScanStats(int partitionsTotal, int partitionsRead, int partitionsPruned) {
}