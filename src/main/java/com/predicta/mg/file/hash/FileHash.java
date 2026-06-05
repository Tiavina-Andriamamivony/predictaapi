package com.predicta.mg.file.hash;

import com.predicta.mg.PojaGenerated;

@PojaGenerated
public record FileHash(FileHashAlgorithm algorithm, String value) {}
