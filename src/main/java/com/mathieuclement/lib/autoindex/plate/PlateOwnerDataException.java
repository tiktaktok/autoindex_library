package com.mathieuclement.lib.autoindex.plate;

/**
 * Exception when parsing owner.
 */
public class PlateOwnerDataException extends Exception {
    private PlateOwner plateOwner;

    public PlateOwnerDataException(String message, PlateOwner plateOwner) {
        super(message);
        this.plateOwner = plateOwner;
    }
}
