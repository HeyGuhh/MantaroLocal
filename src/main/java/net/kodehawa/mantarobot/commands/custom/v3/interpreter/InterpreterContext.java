/*
 * Copyright (C) 2016 Kodehawa
 *
 * Mantaro is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * Mantaro is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Mantaro. If not, see http://www.gnu.org/licenses/
 *
 */

package net.kodehawa.mantarobot.commands.custom.v3.interpreter;

import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.kodehawa.mantarobot.core.command.TextContext;

import java.util.HashMap;
import java.util.Map;

public class InterpreterContext {
    private final Map<String, Object> custom = new HashMap<>();
    private final Map<String, String> vars;
    private final Map<String, Operation> operations;
    private final TextContext commandContext;

    public InterpreterContext(Map<String, String> vars, Map<String, Operation> operations, TextContext ctx) {
        this.vars = vars;
        this.operations = operations;
        this.commandContext = ctx;
    }

    public Map<String, String> vars() {
        return vars;
    }

    public Map<String, Operation> operations() {
        return operations;
    }

    @SuppressWarnings("unused")
    public MessageReceivedEvent event() {
        return commandContext.getEvent();
    }

    public void set(String key, Object value) {
        custom.put(key, value);
    }

    public TextContext getCommandContext() {
        return commandContext;
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) custom.get(key);
    }
}
