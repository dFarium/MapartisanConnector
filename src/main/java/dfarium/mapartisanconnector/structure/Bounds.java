package dfarium.mapartisanconnector.structure;

/** Inclusive block bounds, relative to the session origin. */
public record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public static Bounds of(BuildStructure structure) {
        return new Bounds(0, 0, 0, structure.sizeX() - 1, structure.sizeY() - 1, structure.sizeZ() - 1);
    }

    public Bounds union(Bounds other) {
        return new Bounds(
                Math.min(minX, other.minX), Math.min(minY, other.minY), Math.min(minZ, other.minZ),
                Math.max(maxX, other.maxX), Math.max(maxY, other.maxY), Math.max(maxZ, other.maxZ));
    }

    public int sizeX() {
        return maxX - minX + 1;
    }

    public int sizeY() {
        return maxY - minY + 1;
    }

    public int sizeZ() {
        return maxZ - minZ + 1;
    }

    public long volume() {
        return (long) sizeX() * sizeY() * sizeZ();
    }

    @Override
    public String toString() {
        return "[" + minX + "," + minY + "," + minZ + " → " + maxX + "," + maxY + "," + maxZ + "]";
    }
}
