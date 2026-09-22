/*
       Copyright 2019-2021 IBM Corp, All Rights Reserved
       Copyright 2023-2024 Kyndryl, All Rights Reserved

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */

package com.ibm.hybrid.cloud.sample.stocktrader.executioncontrol.json;

/** JSON-B POJO class representing an exception resolution request */
public class ResolveRequest {
    //Optional, and its absence is meaningful: null selects the single-event
    //ASSIGNED -> RESOLVED path, while a supplied owner selects the composite
    //OPEN -> ASSIGNED -> RESOLVED path, so it is never defaulted here
    private String owner;
    private String resolutionNote;


    public ResolveRequest() { //default constructor
    }

    public ResolveRequest(String initialOwner, String initialResolutionNote) {
        setOwner(initialOwner);
        setResolutionNote(initialResolutionNote);
    }

    public String getOwner() {
        return owner;
    }

    public void setOwner(String newOwner) {
        owner = newOwner;
    }

    public String getResolutionNote() {
        return resolutionNote;
    }

    public void setResolutionNote(String newResolutionNote) {
        resolutionNote = newResolutionNote;
    }
}
