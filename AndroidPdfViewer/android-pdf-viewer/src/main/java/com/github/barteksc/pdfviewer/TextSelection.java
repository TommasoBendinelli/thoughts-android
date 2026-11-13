package com.github.barteksc.pdfviewer;

final class TextSelection {

    int basePage;
    int baseChar;
    int extentPage;
    int extentChar;

    boolean isEmpty() {
        return basePage == extentPage && baseChar == extentChar;
    }

    int startPage() {
        return compare(basePage, baseChar, extentPage, extentChar) <= 0 ? basePage : extentPage;
    }

    int startChar() {
        return compare(basePage, baseChar, extentPage, extentChar) <= 0 ? baseChar : extentChar;
    }

    int endPage() {
        return compare(basePage, baseChar, extentPage, extentChar) <= 0 ? extentPage : basePage;
    }

    int endChar() {
        return compare(basePage, baseChar, extentPage, extentChar) <= 0 ? extentChar : baseChar;
    }

    static int compare(int leftPage, int leftChar, int rightPage, int rightChar) {
        if (leftPage != rightPage) {
            return Integer.compare(leftPage, rightPage);
        }
        return Integer.compare(leftChar, rightChar);
    }
}
