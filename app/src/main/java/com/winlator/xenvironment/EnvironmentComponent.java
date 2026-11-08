package com.winlator.xenvironment;

public abstract class EnvironmentComponent {
    protected XEnvironment environment;

    /** AleksOS: компонент, запущенный вне XEnvironment.addComponent (ещё одна программа). */
    public void setEnvironment(XEnvironment environment) {
        this.environment = environment;
    }

    public abstract void start();

    public abstract void stop();
}