package com.blockbyblockwest.fest.proxylink;



public enum ServerType {

  HUB, STAGE_1, STAGE_2, STAGE_3, PAINTBALL;

  public boolean isStage() {
    switch (this) {
      case STAGE_1:
      case STAGE_2:
      case STAGE_3:
        return true;
    }
    return false;
  }

  public static ServerType getServerType(String parser) {
    for (ServerType ts : ServerType.values()) {
      if (ts.name().equals(parser.toUpperCase())) {
        return ts;
      }
    }
    return null;
  }

}
