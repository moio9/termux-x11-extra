package com.termux.x11.mapper;

import android.widget.Button;
import android.view.ViewGroup;
import java.util.List;
import java.util.ArrayList;

public class DeleteButtonCommand implements Command {
    private final List<Button> buttons;
    private final ViewGroup container;
    private final Runnable saveAction;
    private final Runnable removeAction;

    public DeleteButtonCommand(List<Button> buttons, ViewGroup container, Runnable saveAction, Runnable removeAction) {
        this.buttons = new ArrayList<>(buttons);
        this.container = container;
        this.saveAction = saveAction;
        this.removeAction = removeAction;
    }

    @Override
    public void execute() {
        for (Button btn : buttons) {
            container.removeView(btn);
        }
        if (removeAction != null) removeAction.run();
    }

    @Override
    public void undo() {
        for (Button btn : buttons) {
            if (btn.getParent() == null) {
                container.addView(btn);
            }
        }
        if (saveAction != null) saveAction.run();
    }
}
