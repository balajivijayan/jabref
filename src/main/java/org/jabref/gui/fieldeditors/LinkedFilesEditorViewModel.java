package org.jabref.gui.fieldeditors;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import javafx.beans.property.ListProperty;
import javafx.beans.property.SimpleListProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;

import org.jabref.Globals;
import org.jabref.gui.DialogService;
import org.jabref.gui.externalfiles.DownloadExternalFile;
import org.jabref.gui.externalfiles.FileDownloadTask;
import org.jabref.gui.externalfiletype.ExternalFileType;
import org.jabref.gui.externalfiletype.ExternalFileTypes;
import org.jabref.gui.util.BindingsHelper;
import org.jabref.gui.util.FileDialogConfiguration;
import org.jabref.gui.util.TaskExecutor;
import org.jabref.logic.l10n.Localization;
import org.jabref.logic.net.URLDownload;
import org.jabref.logic.util.OS;
import org.jabref.logic.util.io.FileFinder;
import org.jabref.logic.util.io.FileFinders;
import org.jabref.logic.util.io.FileUtil;
import org.jabref.model.database.BibDatabaseContext;
import org.jabref.model.entry.FileFieldParser;
import org.jabref.model.entry.FileFieldWriter;
import org.jabref.model.entry.LinkedFile;
import org.jabref.preferences.JabRefPreferences;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class LinkedFilesEditorViewModel extends AbstractEditorViewModel {
    private static final Log LOGGER = LogFactory.getLog(LinkedFilesEditorViewModel.class);

    private ListProperty<LinkedFileViewModel> files = new SimpleListProperty<>(FXCollections.observableArrayList());
    private DialogService dialogService;
    private BibDatabaseContext databaseContext;
    private TaskExecutor taskExecutor;

    public LinkedFilesEditorViewModel(DialogService dialogService, BibDatabaseContext databaseContext, TaskExecutor taskExecutor) {
        this.dialogService = dialogService;
        this.databaseContext = databaseContext;
        this.taskExecutor = taskExecutor;
        BindingsHelper.bindContentBidirectional(
                files,
                text,
                this::getStringRepresentation,
                this::parseToFileViewModel
        );
    }

    private List<LinkedFileViewModel> parseToFileViewModel(String stringValue) {
        return FileFieldParser.parse(stringValue).stream()
                .map(LinkedFileViewModel::new)
                .collect(Collectors.toList());
    }

    private String getStringRepresentation(List<LinkedFileViewModel> filesValue) {
        return FileFieldWriter.getStringRepresentation(
                filesValue.stream().map(LinkedFileViewModel::getFile).collect(Collectors.toList()));
    }

    public ObservableList<LinkedFileViewModel> getFiles() {
        return files.get();
    }

    public ListProperty<LinkedFileViewModel> filesProperty() {
        return files;
    }

    public void addNewFile() {
        List<Path> fileDirectories = databaseContext.getFileDirectoriesAsPaths(Globals.prefs.getFileDirectoryPreferences());
        Path workingDirectory = fileDirectories.stream()
                .findFirst()
                .orElse(Paths.get(Globals.prefs.get(JabRefPreferences.WORKING_DIRECTORY)));

        FileDialogConfiguration fileDialogConfiguration = new FileDialogConfiguration.Builder()
                .withInitialDirectory(workingDirectory)
                .build();

        dialogService.showFileOpenDialog(fileDialogConfiguration).ifPresent(
                newFile -> {
                    LinkedFile newLinkedFile = LinkedFile.fromFile(newFile, fileDirectories);
                    files.add(new LinkedFileViewModel(newLinkedFile));
                }
        );
    }

    public void fetchFulltext() {
        if (!entry.isPresent()) {
            return;
        }

        final List<Path> dirs = databaseContext.getFileDirectoriesAsPaths(Globals.prefs.getFileDirectoryPreferences());
        final List<String> extensions = ExternalFileTypes.getInstance().getExternalFileTypeSelection().stream().map(ExternalFileType::getExtension).collect(Collectors.toList());

        // Run the search operation:
        FileFinder fileFinder = FileFinders.constructFromConfiguration(Globals.prefs.getAutoLinkPreferences());
        List<Path> newFiles = fileFinder.findAssociatedFiles(entry.get(), dirs, extensions);

        boolean newAssociatedFileFound = false;
        for (Path newFile : newFiles) {
            boolean alreadyLinked = files.get().stream()
                    .map(file -> file.findIn(dirs))
                    .anyMatch(file -> file.isPresent() && file.get().equals(newFile));
            if (!alreadyLinked) {
                LinkedFile newLinkedFile = LinkedFile.fromFile(newFile, dirs);
                files.add(new LinkedFileViewModel(newLinkedFile));
                newAssociatedFileFound = true;
            }
        }

        if (newAssociatedFileFound) {
            dialogService.notify(Localization.lang("Finished automatically setting external links."));
        } else {
            dialogService.notify(
                    Localization.lang("Finished automatically setting external links.")
                            + " "
                            + Localization.lang("No files found."));

            // auto download file as no file found before
            // TODO: implement
            //frame.getCurrentBasePanel().runCommand(Actions.DOWNLOAD_FULL_TEXT);
        }
    }

    public void addFromURL() {
        Optional<String> urlText = dialogService.showInputDialogAndWait(
                Localization.lang("Download file"), Localization.lang("Enter URL to download"));
        if (urlText.isPresent()) {
            try {
                URL url = new URL(urlText.get());
                URLDownload urlDownload = new URLDownload(url);

                Optional<ExternalFileType> suggestedType = inferFileType(urlDownload);
                String suggestedTypeName = suggestedType.map(ExternalFileType::getName).orElse("");
                List<Path> fileDirectories = databaseContext.getFileDirectoriesAsPaths(Globals.prefs.getFileDirectoryPreferences());
                Path destination = constructSuggestedPath(suggestedType, fileDirectories);

                LinkedFileViewModel temporaryDownloadFile = new LinkedFileViewModel(new LinkedFile("", url, suggestedTypeName));
                files.add(temporaryDownloadFile);
                FileDownloadTask downloadTask = new FileDownloadTask(url, destination);
                temporaryDownloadFile.downloadProgressProperty().bind(downloadTask.progressProperty());
                downloadTask.setOnSucceeded(event -> {
                    files.remove(temporaryDownloadFile);
                    LinkedFile newLinkedFile = LinkedFile.fromFile(destination, fileDirectories);
                    files.add(new LinkedFileViewModel(newLinkedFile));
                });
                downloadTask.setOnFailed(event ->
                        dialogService.showErrorDialogAndWait("", downloadTask.getException()));
                taskExecutor.execute(downloadTask);
            } catch (MalformedURLException exception) {
                dialogService.showErrorDialogAndWait(
                        Localization.lang("Invalid URL"),
                        exception
                );
            }
        }
    }

    private Optional<ExternalFileType> inferFileType(URLDownload urlDownload) {
        Optional<ExternalFileType> suggestedType = inferFileTypeFromMimeType(urlDownload);

        // If we did not find a file type from the MIME type, try based on extension:
        if (!suggestedType.isPresent()) {
            suggestedType = inferFileTypeFromURL(urlDownload.getSource().toExternalForm());
        }
        return suggestedType;
    }

    private Path constructSuggestedPath(Optional<ExternalFileType> suggestedType, List<Path> fileDirectories) {
        String suffix = suggestedType.map(ExternalFileType::getExtension).orElse("");
        String suggestedName = getSuggestedFileName(suffix);
        Path directory;
        if (fileDirectories.isEmpty()) {
            directory = null;
        } else {
            directory = fileDirectories.get(0);
        }
        final Path suggestDir = directory == null ? Paths.get(System.getProperty("user.home")) : directory;
        return suggestDir.resolve(suggestedName);
    }

    private Optional<ExternalFileType> inferFileTypeFromMimeType(URLDownload urlDownload) {
        try {
            // TODO: what if this takes long time?
            String mimeType = urlDownload.getMimeType(); // Read MIME type
            if (mimeType != null) {
                LOGGER.debug("MIME Type suggested: " + mimeType);
                return ExternalFileTypes.getInstance().getExternalFileTypeByMimeType(mimeType);
            } else {
                return Optional.empty();
            }
        } catch (IOException ex) {
            LOGGER.debug("Error while inferring MIME type for URL " + urlDownload.getSource(), ex);
            return Optional.empty();
        }
    }

    private Optional<ExternalFileType> inferFileTypeFromURL(String url) {
        String extension = DownloadExternalFile.getSuffix(url);
        if (extension != null) {
            return ExternalFileTypes.getInstance().getExternalFileTypeByExt(extension);
        } else {
            return Optional.empty();
        }
    }

    private String getSuggestedFileName(String suffix) {
        String plannedName = FileUtil.createFileNameFromPattern(databaseContext.getDatabase(), entry.get(),
                Globals.prefs.get(JabRefPreferences.IMPORT_FILENAMEPATTERN),
                Globals.prefs.getLayoutFormatterPreferences(Globals.journalAbbreviationLoader));

        if (!suffix.isEmpty()) {
            plannedName += "." + suffix;
        }

        /*
        * [ 1548875 ] download pdf produces unsupported filename
        *
        * http://sourceforge.net/tracker/index.php?func=detail&aid=1548875&group_id=92314&atid=600306
        * FIXME: rework this! just allow alphanumeric stuff or so?
        * https://msdn.microsoft.com/en-us/library/windows/desktop/aa365247(v=vs.85).aspx#naming_conventions
        * http://superuser.com/questions/358855/what-characters-are-safe-in-cross-platform-file-names-for-linux-windows-and-os
        * https://support.apple.com/en-us/HT202808
        */
        if (OS.WINDOWS) {
            plannedName = plannedName.replaceAll("\\?|\\*|\\<|\\>|\\||\\\"|\\:|\\.$|\\[|\\]", "");
        } else if (OS.OS_X) {
            plannedName = plannedName.replace(":", "");
        }

        return plannedName;
    }

}
