package com.example;

import java.util.ArrayList;
import java.util.List;

/**
 * A deliberately minimal in-memory todo CLI with obvious feature gaps for an
 * epic to fill in:
 *   - tasks are not persisted (lost on exit),
 *   - there is no `done <id>` or `delete <id>` command,
 *   - there are no priority levels or a `list --priority` filter.
 *
 * Supported today: `add <text>` and `list`.
 */
public final class TodoCli {

    private final List<String> tasks = new ArrayList<>();

    public void add(String text) {
        tasks.add(text);
    }

    public List<String> list() {
        return List.copyOf(tasks);
    }

    public static void main(String[] args) {
        TodoCli cli = new TodoCli();
        if (args.length == 0) {
            System.out.println("usage: todo <add|list> [args]");
            return;
        }
        switch (args[0]) {
            case "add" -> {
                cli.add(String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)));
                System.out.println("added");
            }
            case "list" -> cli.list().forEach(System.out::println);
            default -> System.out.println("unknown command: " + args[0]);
        }
    }
}
