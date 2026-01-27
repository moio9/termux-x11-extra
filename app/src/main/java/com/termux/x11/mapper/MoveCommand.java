package com.termux.x11.mapper;

import android.widget.Button;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

public class MoveCommand implements Command {
    private final Map<Button, Float> oldX = new HashMap<>();
    private final Map<Button, Float> oldY = new HashMap<>();
    private final Map<Button, Float> newX = new HashMap<>();
    private final Map<Button, Float> newY = new HashMap<>();
    private final List<Button> buttons;
    private final Runnable saveAction;

    public MoveCommand(List<Button> buttons, Map<Button, Float> startX, Map<Button, Float> startY, Runnable saveAction) {
        this.buttons = buttons;
        this.saveAction = saveAction;
        for (Button btn : buttons) {
            this.oldX.put(btn, startX.get(btn));
            this.oldY.put(btn, startY.get(btn));
            this.newX.put(btn, btn.getX());
            this.newY.put(btn, btn.getY());
        }
    }

    @Override
    public void execute() {
        // Redo
        for (Button btn : buttons) {
            btn.setX(newX.get(btn));
            btn.setY(newY.get(btn));
        }
        if (saveAction != null) saveAction.run();
    }

    @Override
    public void undo() {
        for (Button btn : buttons) {
            btn.setX(oldX.get(btn));
            btn.setY(oldY.get(btn));
        }
        if (saveAction != null) saveAction.run();
    }
}
