package com.termux.x11.mapper;

import android.widget.Button;
import android.view.ViewGroup;

public class AddButtonCommand implements Command {
    private final Button button;
    private final ViewGroup container;
    private final Runnable saveAction;
    private final Runnable removeAction;

    public AddButtonCommand(Button button, ViewGroup container, Runnable saveAction, Runnable removeAction) {
        this.button = button;
        this.container = container;
        this.saveAction = saveAction;
        this.removeAction = removeAction;
    }

    @Override
    public void execute() {
        if (button.getParent() == null) {
            container.addView(button);
        }
        if (saveAction != null) saveAction.run();
    }

    @Override
    public void undo() {
        container.removeView(button);
        if (removeAction != null) removeAction.run();
    }
}
