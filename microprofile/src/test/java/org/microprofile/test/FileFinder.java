package org.microprofile.test;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedList;
import java.util.List;

public class FileFinder {

    public static void main(String[] args) throws IOException {
        Path startingDir = Paths.get("D:\\repositories\\PublicCMS\\data\\publiccms");

        List<String> result = new LinkedList<String>();

        Files.walkFileTree(startingDir, new FilterFilesVisitor(result));

        System.out.println("result.size()=" + result.size());
        for (String name : result) {
            System.out.println(name);
        }
    }

    private static class FilterFilesVisitor extends SimpleFileVisitor<Path> {

        private List<String> result = new LinkedList<String>();

        public FilterFilesVisitor(List<String> result) {
            this.result = result;
        }

        public FileVisitResult preVisitDirectory(Path file, BasicFileAttributes attrs) {
            result.add(file.toString());
            return FileVisitResult.CONTINUE;
        }
        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            result.add(file.toString());
            return FileVisitResult.CONTINUE;
        }
    }
}
