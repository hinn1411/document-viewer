package io.github.hinn1411.docviewer.search.model;

/** Who is searching, from the gateway's identity headers (A1). */
public record Caller(String userId, String dealershipId) {}
