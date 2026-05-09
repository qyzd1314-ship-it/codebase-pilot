package com.yupi.yuaiagent.repo.service;

import cn.hutool.core.io.FileUtil;
import com.yupi.yuaiagent.constant.FileConstant;
import org.springframework.stereotype.Service;

import java.io.File;

@Service
public class RepoWorkspaceService {

    public File getReposRootDir() {
        return FileUtil.mkdir(FileConstant.FILE_SAVE_DIR + "/repos");
    }

    public File buildRepoDir(String repoId) {
        return new File(getReposRootDir(), repoId);
    }
}
