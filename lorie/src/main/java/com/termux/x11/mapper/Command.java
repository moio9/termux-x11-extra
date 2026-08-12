package com.termux.x11.mapper;

public interface Command {
    void execute();
    void undo();
}
